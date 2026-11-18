package com.localmind.app.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTest {

    @Test
    fun `detects common quantization tags`() {
        assertEquals("Q4_K_M", detectQuantization("llama-3.2-3b-instruct-Q4_K_M.gguf"))
        assertEquals("Q8_0", detectQuantization("model.q8_0.gguf"))
        assertEquals("IQ2_XXS", detectQuantization("mistral-7b-IQ2_XXS.gguf"))
        assertEquals("F16", detectQuantization("tinyllama-f16.gguf"))
    }

    @Test
    fun `does not confuse parameter counts with quantization`() {
        assertNull(detectQuantization("qwen2.5-7b-instruct.gguf"))
    }

    @Test
    fun `detects gguf format case-insensitively`() {
        assertEquals(ModelFormat.GGUF, detectFormat("Model.GGUF"))
        assertEquals(ModelFormat.UNKNOWN, detectFormat("model.bin"))
    }

    @Test
    fun `only gguf models are usable`() {
        val base = LocalModel(
            id = "1",
            displayName = "m.gguf",
            uri = "content://m",
            sizeBytes = 1,
            importedAt = 0,
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
        )
        assertTrue(base.isUsable)
        assertTrue(base.isQuantized)
        assertFalse(base.copy(format = ModelFormat.UNKNOWN).isUsable)
    }
}
