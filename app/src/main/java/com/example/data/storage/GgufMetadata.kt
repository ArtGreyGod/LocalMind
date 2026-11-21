package com.example.data.storage

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal little-endian reader for the GGUF header (magic, version, and the
 * key/value metadata section). Tensor data is never read: arrays in the KV
 * section (e.g. the tokenizer vocabulary) are walked and skipped, so parsing
 * costs only a handful of buffered reads even for multi-GB weights.
 */
object GgufMetadataParser {

    data class GgufMetadata(
        val version: Int,
        val architecture: String?,
        val contextLength: Int?,
        val blockCount: Int?,
        val embeddingLength: Int?,
        val parameterCount: Long?
    )

    private const val MAGIC_GGUF = 0x46554747L // "GGUF" little-endian

    // gguf_metadata_value_type
    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    fun parse(file: File): GgufMetadata? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
            val magic = readUint32(input)
            if (magic != MAGIC_GGUF) return null
            val version = readUint32(input).toInt()
            if (version < 1) return null
            val tensorCount = readUint64(input)
            val kvCount = readUint64(input)
            if (kvCount > 100_000uL) return null

            var architecture: String? = null
            var contextLength: Int? = null
            var blockCount: Int? = null
            var embeddingLength: Int? = null
            var parameterCount: Long? = null

            repeat(kvCount.toInt()) {
                val key = readGgufString(input)
                val valueType = readUint32(input).toInt()
                when (key) {
                    "general.architecture" -> if (valueType == TYPE_STRING) architecture = readGgufString(input)
                    "general.parameter_count" -> if (isUInt64Type(valueType)) parameterCount = readNumericValue(input, valueType).toLong()

                    else -> {
                        val suffix = key.substringAfter('.').substringBeforeLast('.')
                        val leaf = key.substringAfterLast('.')
                        when {
                            suffix != key && leaf == "context_length" && isUInt64Type(valueType) ->
                                contextLength = readNumericValue(input, valueType).toInt()

                            suffix != key && leaf == "block_count" && isUInt64Type(valueType) ->
                                blockCount = readNumericValue(input, valueType).toInt()

                            suffix != key && leaf == "embedding_length" && isUInt64Type(valueType) ->
                                embeddingLength = readNumericValue(input, valueType).toInt()

                            else -> skipValue(input, valueType)
                        }
                    }
                }
            }
            check(tensorCount >= 0uL) { "Corrupt GGUF tensor count" }
            GgufMetadata(
                version = version,
                architecture = architecture,
                contextLength = contextLength,
                blockCount = blockCount,
                embeddingLength = embeddingLength,
                parameterCount = parameterCount
            )
        }
    }.getOrNull()

    private fun isUInt64Type(valueType: Int): Boolean =
        valueType in intArrayOf(TYPE_UINT8, TYPE_INT8, TYPE_UINT16, TYPE_INT16, TYPE_UINT32, TYPE_INT32, TYPE_UINT64, TYPE_INT64)

    private fun readNumericValue(input: DataInputStream, valueType: Int): Long = when (valueType) {
        TYPE_UINT8, TYPE_INT8 -> input.readByte().toLong() and 0xFFL
        TYPE_UINT16, TYPE_INT16 -> readUint16(input).toLong()
        TYPE_UINT32, TYPE_INT32 -> readUint32(input).toLong()
        TYPE_UINT64, TYPE_INT64 -> readUint64(input).toLong()
        else -> error("Expected numeric GGUF value type, got $valueType")
    }

    private fun readGgufString(input: DataInputStream): String {
        val len = readUint64(input)
        if (len > 64uL * 1024uL * 1024uL) error("Unreasonable GGUF string length $len")
        val bytes = ByteArray(len.toInt())
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun skipValue(input: DataInputStream, valueType: Int) {
        when (valueType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipFully(input, 1)
            TYPE_UINT16, TYPE_INT16 -> skipFully(input, 2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipFully(input, 4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipFully(input, 8)
            TYPE_STRING -> readGgufString(input)
            TYPE_ARRAY -> {
                val elemType = readUint32(input).toInt()
                val count = readUint64(input)
                if (count > 10uL * 1024uL * 1024uL) error("Unreasonable GGUF array length $count")
                when (elemType) {
                    TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipFully(input, count.toInt())
                    TYPE_UINT16, TYPE_INT16 -> skipFully(input, count.toInt() * 2)
                    TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipFully(input, count.toInt() * 4)
                    TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipFully(input, count.toInt() * 8)
                    TYPE_STRING -> repeat(count.toInt()) { readGgufString(input) }
                    else -> repeat(count.toInt()) { skipValue(input, elemType) }
                }
            }
            else -> error("Unknown GGUF value type $valueType")
        }
    }

    private fun skipFully(input: DataInputStream, byteCount: Int) {
        if (byteCount <= 0) return
        var remaining = byteCount.toLong()
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val step = minOf(remaining, scratch.size.toLong()).toInt()
            input.readFully(scratch, 0, step)
            remaining -= step
        }
    }

    private fun readUint16(input: DataInputStream): Int {
        val b0 = input.readByte().toInt() and 0xFF
        val b1 = input.readByte().toInt() and 0xFF
        return (b1 shl 8) or b0
    }

    private fun readUint32(input: DataInputStream): Long {
        val b0 = input.readByte().toLong() and 0xFFL
        val b1 = input.readByte().toLong() and 0xFFL
        val b2 = input.readByte().toLong() and 0xFFL
        val b3 = input.readByte().toLong() and 0xFFL
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun readUint64(input: DataInputStream): ULong {
        var value = 0uL
        for (shift in 0 until 64 step 8) {
            val b = input.readByte().toLong() and 0xFFL
            value = value or (b.toULong() shl shift)
        }
        return value
    }
}
