package com.localmind.app.core.gguf

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads the GGUF header, metadata and tensor descriptors without loading tensor
 * data. Used to validate a file and to estimate resource requirements before a
 * native backend ever maps it.
 *
 * Format reference: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
 */
object GgufParser {

    const val MAGIC = "GGUF"
    private const val MAX_METADATA_BYTES = 128L * 1024 * 1024
    private const val MAX_STRING_BYTES = 64L * 1024 * 1024
    private const val MAX_ARRAY_ELEMENTS = 10_000_000L
    private const val MAX_TENSORS = 1_000_000L
    private const val MAX_METADATA_ENTRIES = 1_000_000L
    private const val MAX_DIMS = 8
    private const val MAX_PARAMETER_COUNT = 10_000_000_000_000L

    fun parse(input: InputStream): GgufMetadata {
        val reader = CountingReader(input, MAX_METADATA_BYTES)
        val magic = String(reader.readBytes(4), Charsets.US_ASCII)
        if (magic != MAGIC) {
            throw GgufFormatException("Not a GGUF file (missing GGUF header).")
        }

        val version = reader.readU32().toInt()
        if (version !in 2..3) {
            throw GgufFormatException(
                "Unsupported GGUF version $version. LocalMind supports version 2 and 3.",
            )
        }

        val tensorCount = reader.readU64()
        if (tensorCount <= 0 || tensorCount > MAX_TENSORS) {
            throw GgufFormatException("Invalid tensor count: $tensorCount.")
        }
        val metadataCount = reader.readU64()
        if (metadataCount < 0 || metadataCount > MAX_METADATA_ENTRIES) {
            throw GgufFormatException("Invalid metadata entry count: $metadataCount.")
        }

        val metadata = MetadataBuilder()
        repeat(metadataCount.toInt()) {
            val key = reader.readString()
            val valueType = reader.readU32().toInt()
            readMetadataValue(reader, key, valueType, metadata)
        }

        var parameterCount = 0L
        var counted = false
        val tensorTypes = linkedMapOf<Int, Int>()
        val declaredTensorCount = tensorCount.toInt()

        repeat(declaredTensorCount) {
            reader.readString() // tensor name
            val dimensions = reader.readU32().toInt()
            if (dimensions !in 1..MAX_DIMS) {
                throw GgufFormatException("Invalid tensor dimension count: $dimensions.")
            }
            var elements = 1L
            repeat(dimensions) {
                val dimension = reader.readU64()
                if (dimension <= 0) throw GgufFormatException("Invalid tensor dimension: $dimension.")
                elements = safeMultiply(elements, dimension)
            }
            val type = reader.readU32().toInt()
            reader.readU64() // relative offset, not needed for metadata validation

            parameterCount = safeAdd(parameterCount, elements)
            counted = true
            tensorTypes[type] = (tensorTypes[type] ?: 0) + 1
        }

        return GgufMetadata(
            version = version,
            tensorCount = declaredTensorCount,
            metadataCount = metadataCount.toInt(),
            architecture = metadata.architecture,
            modelName = metadata.modelName,
            trainedContextLength = metadata.contextLength,
            embeddingLength = metadata.embeddingLength,
            blockCount = metadata.blockCount,
            headCount = metadata.headCount,
            headCountKv = metadata.headCountKv,
            parameterCount = if (counted) parameterCount else null,
            tensorTypes = tensorTypes,
            chatTemplate = metadata.chatTemplate,
            splitCount = metadata.splitCount,
            splitIndex = metadata.splitIndex,
        )
    }

    private fun readMetadataValue(
        reader: CountingReader,
        key: String,
        valueType: Int,
        metadata: MetadataBuilder,
    ) {
        when (valueType) {
            TYPE_UINT8 -> metadata.consume(key, reader.readBytes(1)[0].toLong() and 0xFF)
            TYPE_INT8 -> metadata.consume(key, reader.readBytes(1)[0].toLong())
            TYPE_UINT16 -> metadata.consume(key, reader.readU16())
            TYPE_INT16 -> metadata.consume(key, reader.readU16().toShort().toLong())
            TYPE_UINT32 -> metadata.consume(key, reader.readU32())
            TYPE_INT32 -> metadata.consume(key, reader.readU32().toInt().toLong())
            TYPE_FLOAT32 -> metadata.consume(key, Float.fromBits(reader.readU32().toInt()).toDouble())
            TYPE_BOOL -> metadata.consume(key, if (reader.readBytes(1)[0].toInt() != 0) 1L else 0L)
            TYPE_STRING -> {
                val text = reader.readString()
                if (key == "general.architecture" || key == "general.name" || key == "tokenizer.chat_template") {
                    metadata.consume(key, text)
                }
            }
            TYPE_UINT64, TYPE_INT64 -> metadata.consume(key, reader.readU64())
            TYPE_FLOAT64 -> metadata.consume(key, Double.fromBits(reader.readU64()))
            TYPE_ARRAY -> {
                val elementType = reader.readU32().toInt()
                val count = reader.readU64()
                if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
                    throw GgufFormatException("Metadata array '$key' is too large: $count.")
                }
                readArray(reader, key, elementType, count, metadata)
            }
            else -> throw GgufFormatException("Unknown metadata value type $valueType for '$key'.")
        }
    }

    private fun readArray(
        reader: CountingReader,
        key: String,
        elementType: Int,
        count: Long,
        metadata: MetadataBuilder,
    ) {
        // Per-layer KV head counts are stored as arrays. Capture the first scalar
        // for estimation; skip every other array (vocabulary, merges, scores).
        val captureFirst = metadata.wantsFirstArrayValue(key)
        repeat(count.toInt()) { index ->
            when (elementType) {
                TYPE_UINT8 -> {
                    val v = reader.readBytes(1)[0].toLong() and 0xFF
                    if (captureFirst && index == 0) metadata.consume(key, v)
                }
                TYPE_INT8 -> {
                    val v = reader.readBytes(1)[0].toLong()
                    if (captureFirst && index == 0) metadata.consume(key, v)
                }
                TYPE_UINT16 -> {
                    val v = reader.readU16()
                    if (captureFirst && index == 0) metadata.consume(key, v)
                }
                TYPE_INT16 -> reader.readU16()
                TYPE_UINT32 -> {
                    val v = reader.readU32()
                    if (captureFirst && index == 0) metadata.consume(key, v)
                }
                TYPE_INT32 -> reader.readU32()
                TYPE_FLOAT32 -> reader.readU32()
                TYPE_BOOL -> reader.readBytes(1)
                TYPE_STRING -> reader.readString()
                TYPE_UINT64, TYPE_INT64 -> reader.readU64()
                TYPE_FLOAT64 -> reader.readU64()
                TYPE_ARRAY -> throw GgufFormatException("Nested metadata arrays are not supported.")
                else -> throw GgufFormatException("Unknown array element type $elementType for '$key'.")
            }
        }
    }

    private fun safeMultiply(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0L
        if (a > MAX_PARAMETER_COUNT / b) return MAX_PARAMETER_COUNT
        return (a * b).coerceAtMost(MAX_PARAMETER_COUNT)
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (a > MAX_PARAMETER_COUNT - b) MAX_PARAMETER_COUNT else a + b

    private class MetadataBuilder {
        var architecture: String? = null
            private set
        var modelName: String? = null
            private set
        var contextLength: Int? = null
            private set
        var embeddingLength: Int? = null
            private set
        var blockCount: Int? = null
            private set
        var headCount: Int? = null
            private set
        var headCountKv: Int? = null
            private set
        var chatTemplate: String? = null
            private set
        var splitCount: Int? = null
            private set
        var splitIndex: Int? = null
            private set

        fun wantsFirstArrayValue(key: String): Boolean = key.endsWith(".attention.head_count_kv")

        fun consume(key: String, value: Any) {
            when {
                key == "general.architecture" && value is String -> architecture = value
                key == "general.name" && value is String -> modelName = value
                key == "tokenizer.chat_template" && value is String -> chatTemplate = value
                key == "split.count" && value is Number -> splitCount = value.toInt()
                key == "split.no" && value is Number -> splitIndex = value.toInt()
                key.endsWith(".context_length") && value is Number -> contextLength = value.toInt()
                key.endsWith(".embedding_length") && value is Number -> embeddingLength = value.toInt()
                key.endsWith(".block_count") && value is Number -> blockCount = value.toInt()
                key.endsWith(".attention.head_count") && value is Number -> headCount = value.toInt()
                key.endsWith(".attention.head_count_kv") && value is Number -> headCountKv = value.toInt()
            }
        }
    }

    private class CountingReader(
        private val input: InputStream,
        private val maxBytes: Long,
    ) {
        private var consumed = 0L
        private val buffer = ByteArray(8)

        fun readBytes(count: Int): ByteArray {
            if (count < 0) throw GgufFormatException("Negative read length.")
            if (consumed + count > maxBytes) {
                throw GgufFormatException("GGUF metadata exceeds the supported size limit.")
            }
            val result = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(result, offset, count - offset)
                if (read < 0) throw GgufFormatException("Unexpected end of file while reading GGUF metadata.")
                offset += read
            }
            consumed += count
            return result
        }

        fun readU16(): Long {
            readFully(2)
            return (buffer[0].toLong() and 0xFF) or ((buffer[1].toLong() and 0xFF) shl 8)
        }

        fun readU32(): Long {
            readFully(4)
            return (buffer[0].toLong() and 0xFF) or
                ((buffer[1].toLong() and 0xFF) shl 8) or
                ((buffer[2].toLong() and 0xFF) shl 16) or
                ((buffer[3].toLong() and 0xFF) shl 24)
        }

        fun readU64(): Long {
            readFully(8)
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((buffer[index].toLong() and 0xFF) shl (8 * index))
            }
            if (value < 0) throw GgufFormatException("GGUF value exceeds the supported range.")
            return value
        }

        fun readString(): String {
            val length = readU64()
            if (length > MAX_STRING_BYTES) {
                throw GgufFormatException("GGUF string is too large.")
            }
            return String(readBytes(length.toInt()), Charsets.UTF_8)
        }

        private fun readFully(count: Int) {
            if (consumed + count > maxBytes) {
                throw GgufFormatException("GGUF metadata exceeds the supported size limit.")
            }
            var offset = 0
            while (offset < count) {
                val read = input.read(buffer, offset, count - offset)
                if (read < 0) throw GgufFormatException("Unexpected end of file while reading GGUF metadata.")
                offset += read
            }
            consumed += count
        }
    }

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
}

class GgufFormatException(message: String) : IOException(message)

data class GgufMetadata(
    val version: Int,
    val tensorCount: Int,
    val metadataCount: Int,
    val architecture: String?,
    val modelName: String?,
    val trainedContextLength: Int?,
    val embeddingLength: Int?,
    val blockCount: Int?,
    val headCount: Int?,
    val headCountKv: Int?,
    val parameterCount: Long?,
    val tensorTypes: Map<Int, Int>,
    val chatTemplate: String?,
    val splitCount: Int? = null,
    val splitIndex: Int? = null,
) {
    /** True when this file is one part of a multi-file GGUF split. */
    val isSplit: Boolean get() = (splitCount ?: 1) > 1
    /** Human-readable parameter count, for example `7.6B`. */
    fun parameterCountLabel(): String? = parameterCount?.let { count ->
        when {
            count >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000L -> String.format(java.util.Locale.US, "%.0fM", count / 1_000_000.0)
            count >= 1_000L -> String.format(java.util.Locale.US, "%.0fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    /** Dominant tensor type labels, e.g. `Q4_K` or `Q4_K + Q6_K`. */
    fun quantizationLabel(): String? {
        val labels = tensorTypes.entries
            .sortedByDescending { it.value }
            .map { tensorTypeLabel(it.key) }
            .filter { it != null }
            .distinct()
            .take(2)
        return if (labels.isEmpty()) null else labels.joinToString(" + ")
    }

    fun parameterCountForDisplay(): Long? = parameterCount
}

fun tensorTypeLabel(type: Int): String? = when (type) {
    0 -> "F32"
    1 -> "F16"
    2 -> "Q4_0"
    3 -> "Q4_1"
    6 -> "Q5_0"
    7 -> "Q5_1"
    8 -> "Q8_0"
    9 -> "Q8_1"
    10 -> "Q2_K"
    11 -> "Q3_K"
    12 -> "Q4_K"
    13 -> "Q5_K"
    14 -> "Q6_K"
    15 -> "Q8_K"
    16 -> "IQ2_XXS"
    17 -> "IQ2_XS"
    18 -> "IQ3_XXS"
    19 -> "IQ1_S"
    20 -> "IQ4_NL"
    21 -> "IQ3_S"
    22 -> "IQ2_S"
    23 -> "IQ4_XS"
    24 -> "I8"
    25 -> "I16"
    26 -> "I32"
    27 -> "I64"
    28 -> "F64"
    29 -> "IQ1_M"
    30 -> "BF16"
    else -> null
}
