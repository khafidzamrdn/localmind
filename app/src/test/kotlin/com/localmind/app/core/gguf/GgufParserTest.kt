package com.localmind.app.core.gguf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufParserTest {

    @Test
    fun `parses header metadata and tensor descriptors`() {
        val file = GgufWriter().apply {
            header(version = 3, tensorCount = 2, metadataCount = 8)
            string("general.architecture", "llama")
            string("general.name", "Test Model 3B")
            u32("llama.context_length", 8_192)
            u32("llama.embedding_length", 3_072)
            u32("llama.block_count", 28)
            u32("llama.attention.head_count", 24)
            u32Array("llama.attention.head_count_kv", listOf(8, 8, 8, 8))
            u64("llama.vocab_size", 32_000)
            tensor("token_embd.weight", listOf(3_072, 32_000), type = 1)
            tensor("blk.0.attn_q.weight", listOf(3_072, 3_072), type = 12)
        }.toByteArray()

        val metadata = GgufParser.parse(ByteArrayInputStream(file))

        assertEquals(3, metadata.version)
        assertEquals(2, metadata.tensorCount)
        assertEquals(8, metadata.metadataCount)
        assertEquals("llama", metadata.architecture)
        assertEquals("Test Model 3B", metadata.modelName)
        assertEquals(8_192, metadata.trainedContextLength)
        assertEquals(3_072, metadata.embeddingLength)
        assertEquals(28, metadata.blockCount)
        assertEquals(24, metadata.headCount)
        assertEquals(8, metadata.headCountKv)
        assertEquals(3_072L * 32_000L + 3_072L * 3_072L, metadata.parameterCount)
        assertEquals(mapOf(1 to 1, 12 to 1), metadata.tensorTypes)
    }

    @Test
    fun `extracts chat template and labels quantization`() {
        val file = GgufWriter().apply {
            header(version = 3, tensorCount = 3, metadataCount = 2)
            string("tokenizer.chat_template", "{% for m in messages %}{{ m.content }}{% endfor %}")
            string("general.architecture", "qwen2")
            tensor("a", listOf(1_000, 1_000), type = 12)
            tensor("b", listOf(1_000, 1_000), type = 14)
            tensor("c", listOf(1_000, 1_000), type = 1)
        }.toByteArray()

        val metadata = GgufParser.parse(ByteArrayInputStream(file))

        assertTrue(metadata.chatTemplate!!.contains("messages"))
        assertEquals("Q4_K + Q6_K", metadata.quantizationLabel())
        assertEquals("3M", metadata.parameterCountLabel())
    }

    @Test
    fun `rejects file without gguf magic`() {
        val bytes = "NOPE".toByteArray() + ByteArray(32)

        val error = runCatching { GgufParser.parse(ByteArrayInputStream(bytes)) }.exceptionOrNull()

        assertTrue(error is GgufFormatException)
        assertTrue(error!!.message!!.contains("GGUF"))
    }

    @Test
    fun `rejects unsupported versions`() {
        val file = GgufWriter().apply {
            header(version = 1, tensorCount = 1, metadataCount = 0)
            tensor("t", listOf(4), type = 0)
        }.toByteArray()

        val error = runCatching { GgufParser.parse(ByteArrayInputStream(file)) }.exceptionOrNull()

        assertTrue(error is GgufFormatException)
        assertTrue(error!!.message!!.contains("version 1"))
    }

    @Test
    fun `rejects truncated metadata`() {
        val full = GgufWriter().apply {
            header(version = 3, tensorCount = 1, metadataCount = 1)
            string("general.name", "truncated")
            tensor("t", listOf(4), type = 0)
        }.toByteArray()

        val truncated = full.copyOf(full.size - 12)
        val error = runCatching { GgufParser.parse(ByteArrayInputStream(truncated)) }.exceptionOrNull()

        assertTrue(error is GgufFormatException)
        assertTrue(error!!.message!!.contains("Unexpected end of file"))
    }

    @Test
    fun `rejects absurd tensor count`() {
        val file = GgufWriter().apply {
            header(version = 3, tensorCount = 0, metadataCount = 0)
        }.toByteArray()

        val error = runCatching { GgufParser.parse(ByteArrayInputStream(file)) }.exceptionOrNull()

        assertTrue(error is GgufFormatException)
        assertTrue(error!!.message!!.contains("tensor count"))
    }

    @Test
    fun `detects split GGUF`() {
        val file = GgufWriter().apply {
            header(version = 3, tensorCount = 1, metadataCount = 2)
            u32("split.count", 2)
            u32("split.no", 1)
            tensor("t", listOf(4), type = 0)
        }.toByteArray()

        val metadata = GgufParser.parse(ByteArrayInputStream(file))

        assertTrue(metadata.isSplit)
        assertEquals(2, metadata.splitCount)
        assertEquals(1, metadata.splitIndex)
    }

    private class GgufWriter {
        private val out = ByteArrayOutputStream()

        fun header(version: Int, tensorCount: Int, metadataCount: Int) {
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
            u32(version)
            u64(tensorCount.toLong())
            u64(metadataCount.toLong())
        }

        fun string(key: String, value: String) {
            str(key)
            u32(8)
            str(value)
        }

        fun u32(key: String, value: Int) {
            str(key)
            u32(4)
            u32(value)
        }

        fun u64(key: String, value: Long) {
            str(key)
            u32(10)
            u64(value)
        }

        fun u32Array(key: String, values: List<Int>) {
            str(key)
            u32(9)
            u32(4)
            u64(values.size.toLong())
            values.forEach { u32(it) }
        }

        fun tensor(name: String, dims: List<Int>, type: Int) {
            str(name)
            u32(dims.size)
            dims.forEach { u64(it.toLong()) }
            u32(type)
            u64(0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun str(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }

        private fun u32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        private fun u64(value: Long) {
            for (index in 0 until 8) {
                out.write(((value ushr (8 * index)) and 0xFF).toInt())
            }
        }
    }
}
