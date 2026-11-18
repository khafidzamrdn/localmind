package com.localmind.app.core.inference

import com.localmind.app.core.domain.model.LocalModel

/**
 * Runtime configuration for one loaded model. Values are chosen by the user
 * and clamped to what the device and the backend can actually honour.
 */
data class InferenceConfig(
    /** Requested generation threads; 0 means "let the app pick a safe default". */
    val threadCount: Int = 0,
    val contextLength: Int = LocalModel.DEFAULT_CONTEXT_LENGTH,
    val batchSize: Int = LocalModel.DEFAULT_BATCH_SIZE,
    val gpuLayers: Int = 0,
) {
    fun resolvedThreadCount(): Int =
        if (threadCount > 0) threadCount else defaultThreadCount()

    companion object {
        /**
         * Conservative default: leave two cores for the UI/audio stack, never
         * use more than six, and never fewer than two.
         */
        fun defaultThreadCount(
            cores: Int = Runtime.getRuntime().availableProcessors(),
        ): Int = (cores - 2).coerceIn(2, 6)

        fun from(model: LocalModel): InferenceConfig = InferenceConfig(
            threadCount = model.threadCount,
            contextLength = model.contextLength,
            batchSize = model.batchSize,
            gpuLayers = model.gpuLayers,
        )
    }
}

/** Device limits reported to the configuration UI. */
data class DeviceCapabilities(
    val cpuCores: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowRamDevice: Boolean,
    val gpuAcceleration: Boolean,
    val backendName: String,
    val backendIsDemo: Boolean,
) {
    val maxThreads: Int get() = cpuCores.coerceAtLeast(1)
}
