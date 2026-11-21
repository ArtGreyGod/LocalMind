package com.example

import com.example.data.rag.PdfTextExtractor
import com.example.data.storage.GgufMetadataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufMetadataAndPdfHeuristicsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeLittleEndian(build: (ByteBuffer) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        build(buf)
        out.write(buf.array(), 0, buf.position())
        return out.toByteArray()
    }

    private fun putString(buf: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buf.putLong(bytes.size.toLong())
        buf.put(bytes)
    }

    @Test
    fun `gguf parser reads architecture context and block count`() {
        val bytes = writeLittleEndian { buf ->
            buf.putInt(0x46554747) // "GGUF"
            buf.putInt(3)          // version
            buf.putLong(2L)        // tensor count
            buf.putLong(4L)        // kv count
            putString(buf, "general.architecture")
            buf.putInt(8)          // TYPE_STRING
            putString(buf, "qwen2")
            putString(buf, "qwen2.context_length")
            buf.putInt(4)          // TYPE_UINT32
            buf.putInt(32768)
            putString(buf, "qwen2.block_count")
            buf.putInt(4)
            buf.putInt(28)
            putString(buf, "qwen2.embedding_length")
            buf.putInt(4)
            buf.putInt(896)
        }
        val file = File(tmp.newFolder(), "model.gguf")
        file.writeBytes(bytes)

        val meta = GgufMetadataParser.parse(file)
        assertNotNull(meta)
        assertEquals("qwen2", meta!!.architecture)
        assertEquals(32768, meta.contextLength)
        assertEquals(28, meta.blockCount)
        assertEquals(896, meta.embeddingLength)
    }

    @Test
    fun `gguf parser rejects non-gguf files`() {
        val file = File(tmp.newFolder(), "not.gguf")
        file.writeBytes(byteArrayOf(0, 1, 2, 3, 4))
        assertNull(GgufMetadataParser.parse(file))
    }

    @Test
    fun `glyph mojibake heuristic flags identity-h garbage but keeps normal text`() {
        val garbage = buildString {
            repeat(200) { append('\u0004') }
            repeat(50) { append('\u00D2') }
        }
        assertTrue(PdfTextExtractor.looksLikeGlyphMojibake(garbage))
        assertFalse(
            PdfTextExtractor.looksLikeGlyphMojibake(
                "The quick brown fox jumps over the lazy dog. ".repeat(20)
            )
        )
    }
}
