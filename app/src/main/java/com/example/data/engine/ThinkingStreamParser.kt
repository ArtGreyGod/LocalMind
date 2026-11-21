package com.example.data.engine

/**
 * Stateful streaming parser that separates model reasoning/thinking blocks from final answer tokens,
 * safely buffering partial tag prefixes across arbitrary token chunk boundaries.
 *
 * Supports common open-weight reasoning delimiters:
 * - <think>...</think> (Qwen3, DeepSeek-R1, SmolLM3, etc.)
 * - <thought>...</thought>
 * - <|thought|>...<|end_thought|>
 * - <reasoning>...</reasoning>
 * - <|channel>thought...<channel|> (Gemma 4 raw channel delimiters)
 */
class ThinkingStreamParser(
    startInThought: Boolean = false
) {
    data class ParsedChunk(
        val text: String,
        val isThought: Boolean
    )

    private data class TagPair(
        val openTag: String,
        val closeTag: String
    )

    companion object {
        private val TAG_PAIRS = listOf(
            TagPair("<think>", "</think>"),
            TagPair("<thought>", "</thought>"),
            TagPair("<|thought|>", "<|end_thought|>"),
            TagPair("<reasoning>", "</reasoning>"),
            TagPair("<|channel>thought", "<channel|>")
        )
        private val OPEN_TAGS = TAG_PAIRS.map { it.openTag }
        private val CLOSE_TAGS = TAG_PAIRS.map { it.closeTag }
    }

    private var inThought: Boolean = startInThought
    private var activeCloseTag: String? = if (startInThought) "</think>" else null
    private var hasEmittedThoughtContent: Boolean = false
    private var trimLeadingWhitespaceOnAnswer: Boolean = false
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<ParsedChunk> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        val out = mutableListOf<ParsedChunk>()
        drainBuffer(flushAll = false, out = out)
        return out
    }

    fun flush(): List<ParsedChunk> {
        if (buffer.isEmpty()) return emptyList()
        val out = mutableListOf<ParsedChunk>()
        drainBuffer(flushAll = true, out = out)
        return out
    }

    private fun drainBuffer(flushAll: Boolean, out: MutableList<ParsedChunk>) {
        while (buffer.isNotEmpty()) {
            if (!inThought) {
                if (trimLeadingWhitespaceOnAnswer) {
                    var idx = 0
                    while (idx < buffer.length && (buffer[idx] == '\n' || buffer[idx] == '\r')) {
                        idx++
                    }
                    if (idx > 0) {
                        buffer.delete(0, idx)
                        if (buffer.isEmpty()) break
                    }
                    if (!buffer[0].isWhitespace()) {
                        trimLeadingWhitespaceOnAnswer = false
                    }
                }

                // Look for earliest complete opening tag
                var earliestIdx = -1
                var matchedPair: TagPair? = null
                for (pair in TAG_PAIRS) {
                    val idx = buffer.indexOf(pair.openTag)
                    if (idx >= 0 && (earliestIdx == -1 || idx < earliestIdx)) {
                        earliestIdx = idx
                        matchedPair = pair
                    }
                }

                if (matchedPair != null && earliestIdx >= 0) {
                    if (earliestIdx > 0) {
                        emitChunk(out, buffer.substring(0, earliestIdx), isThought = false)
                    }
                    buffer.delete(0, earliestIdx + matchedPair.openTag.length)
                    inThought = true
                    activeCloseTag = matchedPair.closeTag
                    continue
                }

                // Also guard against bare </think> when a template prefilled <think> without us knowing
                val bareCloseIdx = findEarliestCloseTagIndex()
                if (bareCloseIdx != null) {
                    val (idx, closeTag) = bareCloseIdx
                    if (idx > 0) {
                        emitChunk(out, buffer.substring(0, idx), isThought = true)
                    }
                    buffer.delete(0, idx + closeTag.length)
                    inThought = false
                    activeCloseTag = null
                    trimLeadingWhitespaceOnAnswer = true
                    continue
                }

                if (flushAll) {
                    emitChunk(out, buffer.toString(), isThought = false)
                    buffer.clear()
                    break
                }

                val safePrefixLen = safeFlushLength(buffer, OPEN_TAGS + CLOSE_TAGS)
                if (safePrefixLen > 0) {
                    emitChunk(out, buffer.substring(0, safePrefixLen), isThought = false)
                    buffer.delete(0, safePrefixLen)
                }
                break
            } else {
                if (!hasEmittedThoughtContent) {
                    val trimmedLeadingLen = buffer.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) buffer.length else it }
                    if (trimmedLeadingLen < buffer.length) {
                        val matchedOpen = TAG_PAIRS.firstOrNull { buffer.startsWith(it.openTag, startIndex = trimmedLeadingLen) }
                        if (matchedOpen != null) {
                            buffer.delete(0, trimmedLeadingLen + matchedOpen.openTag.length)
                            activeCloseTag = matchedOpen.closeTag
                            continue
                        }
                        if (!flushAll && OPEN_TAGS.any { tag ->
                                val rem = buffer.length - trimmedLeadingLen
                                rem < tag.length && tag.regionMatches(0, buffer, trimmedLeadingLen, rem)
                            }
                        ) {
                            break
                        }
                    }
                }

                // Inside thinking block: scan for matching close tag (or any known close tag)
                val closeMatch = findEarliestCloseTagIndex()
                if (closeMatch != null) {
                    val (idx, closeTag) = closeMatch
                    if (idx > 0) {
                        hasEmittedThoughtContent = true
                        emitChunk(out, buffer.substring(0, idx), isThought = true)
                    }
                    buffer.delete(0, idx + closeTag.length)
                    inThought = false
                    activeCloseTag = null
                    hasEmittedThoughtContent = false
                    trimLeadingWhitespaceOnAnswer = true
                    continue
                }

                if (flushAll) {
                    if (buffer.any { !it.isWhitespace() }) {
                        hasEmittedThoughtContent = true
                    }
                    emitChunk(out, buffer.toString(), isThought = true)
                    buffer.clear()
                    break
                }

                val candidates = activeCloseTag?.let { listOf(it) + CLOSE_TAGS } ?: CLOSE_TAGS
                val safePrefixLen = safeFlushLength(buffer, candidates)
                if (safePrefixLen > 0) {
                    val slice = buffer.substring(0, safePrefixLen)
                    if (slice.any { !it.isWhitespace() }) {
                        hasEmittedThoughtContent = true
                    }
                    emitChunk(out, slice, isThought = true)
                    buffer.delete(0, safePrefixLen)
                }
                break
            }
        }
    }

    private fun findEarliestCloseTagIndex(): Pair<Int, String>? {
        var earliestIdx = -1
        var matchedTag: String? = null
        val tags = if (activeCloseTag != null) {
            (listOf(activeCloseTag!!) + CLOSE_TAGS).distinct()
        } else {
            CLOSE_TAGS
        }
        for (tag in tags) {
            val idx = buffer.indexOf(tag)
            if (idx >= 0 && (earliestIdx == -1 || idx < earliestIdx)) {
                earliestIdx = idx
                matchedTag = tag
            }
        }
        return if (earliestIdx >= 0 && matchedTag != null) earliestIdx to matchedTag else null
    }

    /**
     * Returns how many leading characters of [text] cannot possibly be the start of any [tags].
     */
    private fun safeFlushLength(text: CharSequence, tags: List<String>): Int {
        val maxTagLen = tags.maxOfOrNull { it.length } ?: 0
        if (maxTagLen <= 1) return text.length
        val searchStart = (text.length - (maxTagLen - 1)).coerceAtLeast(0)
        for (start in searchStart until text.length) {
            val suffixLen = text.length - start
            for (tag in tags) {
                if (suffixLen < tag.length && tag.regionMatches(0, text, start, suffixLen)) {
                    return start
                }
            }
        }
        return text.length
    }

    private fun emitChunk(out: MutableList<ParsedChunk>, text: String, isThought: Boolean) {
        if (text.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.isThought == isThought) {
            out[out.lastIndex] = last.copy(text = last.text + text)
        } else {
            out.add(ParsedChunk(text = text, isThought = isThought))
        }
    }
}
