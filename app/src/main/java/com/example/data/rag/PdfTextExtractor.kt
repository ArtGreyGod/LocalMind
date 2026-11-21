package com.example.data.rag

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

data class ExtractedPageSection(
    val sectionLabel: String,
    val text: String
)

/**
 * Pure-Kotlin on-device text & PDF content stream extractor.
 * Supports plain text/code/markdown documents and uncompressed or FlateDecode PDFs
 * without external binary dependencies.
 *
 * Limitations: PDFs with custom encodings / Identity-H subset fonts without a BOM
 * produce glyph-index garbage; such streams are detected and skipped (reported as
 * [PdfExtractionResult.skippedGarbledStreams]) rather than polluting the search index.
 */
object PdfTextExtractor {

    data class PdfExtractionResult(
        val sections: List<ExtractedPageSection>,
        val skippedGarbledStreams: Int
    )

    fun extractPdfSections(pdfBytes: ByteArray): List<ExtractedPageSection> =
        extractPdf(pdfBytes).sections

    fun extractPdf(pdfBytes: ByteArray): PdfExtractionResult {
        if (pdfBytes.isEmpty()) return PdfExtractionResult(emptyList(), 0)
        val rawLatin = String(pdfBytes, StandardCharsets.ISO_8859_1)
        val sections = mutableListOf<ExtractedPageSection>()
        var skippedGarbled = 0

        var searchIdx = 0
        var pageNum = 1
        while (searchIdx < rawLatin.length) {
            val streamTagIdx = rawLatin.indexOf("stream", searchIdx)
            if (streamTagIdx < 0) break

            // Ensure "stream" is preceded by whitespace/dict end, not part of another token
            val headerWindowStart = (streamTagIdx - 220).coerceAtLeast(0)
            val dictWindow = rawLatin.substring(headerWindowStart, streamTagIdx)

            var dataStart = streamTagIdx + "stream".length
            if (dataStart < rawLatin.length && rawLatin[dataStart] == '\r') dataStart++
            if (dataStart < rawLatin.length && rawLatin[dataStart] == '\n') dataStart++

            // Prefer the authoritative /Length from the stream dictionary. Compressed
            // payloads can legitimately contain the byte sequence "endstream", so the
            // textual scan below is only a fallback when /Length is missing or indirect.
            val directLength = Regex("/Length\\s+(\\d+)").find(dictWindow)?.groupValues?.get(1)?.toLongOrNull()
            val endStreamIdx = rawLatin.indexOf("endstream", dataStart)
            if (endStreamIdx < 0) break

            val lengthBasedEnd = directLength?.let { dataStart + it.toInt() }?.takeIf { bounds ->
                bounds > dataStart && bounds <= pdfBytes.size && bounds <= endStreamIdx + 64
            }
            var dataEnd = lengthBasedEnd ?: endStreamIdx
            while (dataEnd > dataStart && (rawLatin[dataEnd - 1] == '\n' || rawLatin[dataEnd - 1] == '\r')) {
                dataEnd--
            }

            if (dataEnd > dataStart) {
                val slice = pdfBytes.copyOfRange(dataStart, dataEnd)
                val isFlate = dictWindow.contains("/FlateDecode") || dictWindow.contains("/Fl ")
                val decodedBytes = if (isFlate) inflateQuietly(slice) else slice
                if (decodedBytes != null) {
                    val contentStream = String(decodedBytes, StandardCharsets.ISO_8859_1)
                    if (contentStream.contains("BT") && (contentStream.contains("Tj") || contentStream.contains("TJ"))) {
                        val text = parsePdfTextOperators(contentStream).trim()
                        if (text.isNotBlank()) {
                            if (looksLikeGlyphMojibake(text)) {
                                skippedGarbled++
                            } else {
                                sections += ExtractedPageSection(
                                    sectionLabel = "Stream $pageNum",
                                    text = text
                                )
                                pageNum++
                            }
                        }
                    }
                }
            }
            searchIdx = endStreamIdx + "endstream".length
        }

        return PdfExtractionResult(sections, skippedGarbled)
    }

    /**
     * Heuristic for glyph-ID mojibake: Identity-H subset fonts emit 2-byte glyph IDs that,
     * decoded as Latin-1, produce unusual runs of control chars and non-word symbols
     * instead of natural language.
     */
    internal fun looksLikeGlyphMojibake(text: String): Boolean {
        val sample = text.take(4000)
        val meaningful = sample.count { !it.isWhitespace() }
        if (meaningful < 24) return false
        val controlChars = sample.count { it.code < 32 && it !in "\n\r\t" }
        val letters = sample.count { it.isLetterOrDigit() }
        val letterRatio = letters.toDouble() / sample.length
        return controlChars >= 8 || letterRatio < 0.35
    }

    private fun inflateQuietly(compressed: ByteArray): ByteArray? {
        val attempts = listOf(false, true)
        for (nowrap in attempts) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream(compressed.size * 2)
                val buf = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buf, 0, count)
                    }
                }
                val result = out.toByteArray()
                if (result.isNotEmpty()) return result
            } catch (_: Throwable) {
                // Retry with raw deflate header mode or skip non-flate stream
            } finally {
                inflater.end()
            }
        }
        return null
    }

    internal fun parsePdfTextOperators(stream: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = stream.length
        var inTextBlock = false

        while (i < len) {
            if (!inTextBlock) {
                val btIdx = stream.indexOf("BT", i)
                if (btIdx < 0) break
                val prevOk = btIdx == 0 || stream[btIdx - 1].isWhitespace()
                val nextOk = btIdx + 2 >= len || stream[btIdx + 2].isWhitespace()
                if (prevOk && nextOk) {
                    inTextBlock = true
                    i = btIdx + 2
                } else {
                    i = btIdx + 2
                }
                continue
            }

            val ch = stream[i]
            if (ch == 'E' && i + 1 < len && stream[i + 1] == 'T' &&
                (i == 0 || stream[i - 1].isWhitespace()) &&
                (i + 2 >= len || stream[i + 2].isWhitespace())
            ) {
                inTextBlock = false
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                i += 2
                continue
            }

            when (ch) {
                '(' -> {
                    val (lit, nextIdx) = readPdfLiteralString(stream, i)
                    sb.append(lit)
                    i = nextIdx
                }
                '<' -> {
                    if (i + 1 < len && stream[i + 1] == '<') {
                        i += 2
                    } else {
                        val (hexStr, nextIdx) = readPdfHexString(stream, i)
                        sb.append(hexStr)
                        i = nextIdx
                    }
                }
                '[' -> {
                    val (arrText, nextIdx) = readPdfArrayStrings(stream, i)
                    sb.append(arrText)
                    i = nextIdx
                }
                'T' -> {
                    if (i + 1 < len) {
                        when (stream[i + 1]) {
                            '*', 'd', 'D', 'm' -> {
                                if (sb.isNotEmpty() && !sb.endsWith("\n") && !sb.endsWith(" ")) {
                                    sb.append(' ')
                                }
                            }
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
        return sb.toString()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun readPdfArrayStrings(stream: String, startIdx: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIdx + 1
        while (i < stream.length && stream[i] != ']') {
            when (stream[i]) {
                '(' -> {
                    val (lit, nextIdx) = readPdfLiteralString(stream, i)
                    sb.append(lit)
                    i = nextIdx
                }
                '<' -> {
                    val (hex, nextIdx) = readPdfHexString(stream, i)
                    sb.append(hex)
                    i = nextIdx
                }
                '-' -> {
                    // Large negative kerning offset in TJ arrays typically encodes a word space
                    val numStart = i + 1
                    var j = numStart
                    while (j < stream.length && stream[j].isDigit()) j++
                    val kern = stream.substring(numStart, j).toIntOrNull() ?: 0
                    if (kern >= 120 && sb.isNotEmpty() && !sb.endsWith(" ")) {
                        sb.append(' ')
                    }
                    i = j.coerceAtLeast(i + 1)
                }
                else -> i++
            }
        }
        return sb.toString() to (i + 1).coerceAtMost(stream.length)
    }

    private fun readPdfLiteralString(stream: String, startIdx: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIdx + 1
        var depth = 1
        while (i < stream.length && depth > 0) {
            val ch = stream[i]
            if (ch == '\\' && i + 1 < stream.length) {
                val next = stream[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    '\\' -> sb.append('\\')
                    '(' -> sb.append('(')
                    ')' -> sb.append(')')
                    '\r', '\n' -> Unit
                    in '0'..'7' -> {
                        var octEnd = i + 2
                        while (octEnd < stream.length && octEnd < i + 4 && stream[octEnd] in '0'..'7') {
                            octEnd++
                        }
                        val code = stream.substring(i + 1, octEnd).toIntOrNull(8) ?: 32
                        sb.append(code.toChar())
                        i = octEnd
                        continue
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else if (ch == '(') {
                depth++
                sb.append(ch)
                i++
            } else if (ch == ')') {
                depth--
                if (depth > 0) sb.append(ch)
                i++
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString() to i
    }

    private fun readPdfHexString(stream: String, startIdx: Int): Pair<String, Int> {
        val endIdx = stream.indexOf('>', startIdx + 1)
        if (endIdx < 0) return "" to (startIdx + 1)
        val hex = stream.substring(startIdx + 1, endIdx).filter { !it.isWhitespace() }
        if (hex.isEmpty()) return "" to (endIdx + 1)
        val padded = if (hex.length % 2 == 1) "${hex}0" else hex
        val bytes = ByteArray(padded.length / 2) { idx ->
            padded.substring(idx * 2, idx * 2 + 2).toIntOrNull(16)?.toByte() ?: 0x20
        }
        val decoded = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        } else {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
        return decoded.filter { it.code >= 32 || it == '\n' } to (endIdx + 1)
    }
}
