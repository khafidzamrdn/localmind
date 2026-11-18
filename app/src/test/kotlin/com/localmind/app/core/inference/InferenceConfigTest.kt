package com.localmind.app.core.inference

import com.localmind.app.core.domain.model.LocalModel
import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceConfigTest {

    @Test
    fun `explicit thread count wins`() {
        assertEquals(5, InferenceConfig(threadCount = 5).resolvedThreadCount())
    }

    @Test
    fun `auto thread count leaves headroom and stays bounded`() {
        assertEquals(2, InferenceConfig.defaultThreadCount(cores = 2))
        assertEquals(2, InferenceConfig.defaultThreadCount(cores = 3))
        assertEquals(4, InferenceConfig.defaultThreadCount(cores = 6))
        assertEquals(6, InferenceConfig.defaultThreadCount(cores = 8))
        assertEquals(6, InferenceConfig.defaultThreadCount(cores = 16))
    }

    @Test
    fun `model config is copied without inventing values`() {
        val model = LocalModel(
            id = "m",
            displayName = "m.gguf",
            uri = "content://m",
            sizeBytes = 1,
            importedAt = 0,
            format = com.localmind.app.core.domain.model.ModelFormat.GGUF,
            quantization = null,
            contextLength = 2048,
            threadCount = 3,
            batchSize = 256,
            gpuLayers = 0,
        )

        val config = InferenceConfig.from(model)

        assertEquals(2048, config.contextLength)
        assertEquals(3, config.threadCount)
        assertEquals(3, config.resolvedThreadCount())
        assertEquals(256, config.batchSize)
        assertEquals(0, config.gpuLayers)
    }
}
