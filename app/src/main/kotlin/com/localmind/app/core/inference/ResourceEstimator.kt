package com.localmind.app.core.inference

import android.app.ActivityManager
import android.content.Context
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.util.formatFileSize

enum class ResourceRisk {
    COMFORTABLE,
    TIGHT,
    RISKY,
}

data class DeviceMemory(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowRamDevice: Boolean,
    val lowMemoryThresholdBytes: Long,
    val underPressure: Boolean,
)

/**
 * Rough but explainable memory estimate. Weights dominate, the KV cache grows
 * with context and the compute buffers grow with batch size. The numbers are
 * intentionally conservative and shown to the user before loading.
 */
data class ResourceEstimate(
    val modelBytes: Long,
    val kvCacheBytes: Long,
    val computeOverheadBytes: Long,
    val totalBytes: Long,
    val availableBytes: Long,
    val totalMemoryBytes: Long,
    val risk: ResourceRisk,
    val notes: List<String>,
) {
    val summary: String
        get() = "≈ ${formatFileSize(totalBytes)} needed · ${formatFileSize(availableBytes)} available"
}

class ResourceEstimator(context: Context) {

    private val appContext = context.applicationContext

    fun deviceMemory(): DeviceMemory {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(info) }
        val total = info.totalMem.takeIf { it > 0 } ?: Runtime.getRuntime().maxMemory()
        val available = info.availMem.takeIf { it > 0 } ?: (total / 2)
        return DeviceMemory(
            totalBytes = total,
            availableBytes = available,
            lowRamDevice = activityManager?.isLowRamDevice == true,
            lowMemoryThresholdBytes = info.threshold.coerceAtLeast(0),
            underPressure = info.lowMemory,
        )
    }

    fun estimate(
        model: LocalModel,
        contextLength: Int = model.contextLength,
        batchSize: Int = model.batchSize,
    ): ResourceEstimate {
        val device = deviceMemory()
        val weights = model.sizeBytes.coerceAtLeast(0)
        val kvCache = estimateKvCache(model, contextLength)
        val overhead = computeOverhead(batchSize)
        val total = weights + kvCache + overhead

        val notes = mutableListOf<String>()
        notes += "Weights ${formatFileSize(weights)} + KV cache ${formatFileSize(kvCache)} + " +
            "compute buffers ${formatFileSize(overhead)}."
        if (device.lowRamDevice) {
            notes += "Android reports this as a low-RAM device; background apps may be killed."
        }
        if (device.underPressure) {
            notes += "The system is currently under memory pressure. Close other apps first."
        }
        if (model.trainedContextLength != null && contextLength < model.trainedContextLength) {
            notes += "Context capped at ${contextLength} tokens; the model was trained for " +
                "${model.trainedContextLength}."
        }

        val risk = when {
            device.availableBytes <= 0 -> ResourceRisk.RISKY
            total > device.availableBytes -> ResourceRisk.RISKY
            total > device.availableBytes * 0.85 -> ResourceRisk.RISKY
            total > device.availableBytes * 0.65 -> ResourceRisk.TIGHT
            total > device.totalBytes * 0.5 -> ResourceRisk.TIGHT
            device.lowRamDevice -> ResourceRisk.TIGHT
            else -> ResourceRisk.COMFORTABLE
        }

        return ResourceEstimate(
            modelBytes = weights,
            kvCacheBytes = kvCache,
            computeOverheadBytes = overhead,
            totalBytes = total,
            availableBytes = device.availableBytes,
            totalMemoryBytes = device.totalBytes,
            risk = risk,
            notes = notes,
        )
    }

    /**
     * KV cache = 2 (K and V) × layers × heads_kv × head_dim × context × 2 bytes.
     * Falls back to a per-context heuristic when the GGUF header did not expose
     * the attention shape.
     */
    private fun estimateKvCache(model: LocalModel, contextLength: Int): Long {
        val context = contextLength.coerceAtLeast(0).toLong()
        val layers = model.attentionLayerCount
        val headCount = model.attentionHeadCount
        val headCountKv = model.attentionHeadCountKv
        val embedding = model.embeddingLength
        if (layers != null && headCount != null && headCountKv != null &&
            embedding != null && headCount > 0 && embedding > 0 && context > 0
        ) {
            val headDim = (embedding / headCount).coerceAtLeast(1)
            return 2L * layers * headCountKv * headDim * context * 2L
        }
        // ~512 MB per 4k context for a typical 7–8B model.
        return context * 128L * 1024L
    }

    private fun computeOverhead(batchSize: Int): Long {
        val batchBlocks = (batchSize.coerceAtLeast(64) / 512).coerceIn(1, 4)
        return (256L + (batchBlocks - 1) * 128L) * 1024 * 1024
    }
}
