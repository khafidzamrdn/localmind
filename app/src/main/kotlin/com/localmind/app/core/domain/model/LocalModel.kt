package com.localmind.app.core.domain.model

/** Container format detected from the file name. */
enum class ModelFormat {
    GGUF,
    UNKNOWN,
}

/**
 * Result of reading the GGUF header. [UNVERIFIED] means the file was registered
 * but its contents have not been inspected yet (for example rows migrated from
 * Milestone 1). Only [VALID] files may be loaded.
 */
enum class ModelValidation {
    UNVERIFIED,
    VALID,
    INVALID,
    MISSING,
}

/** Persisted load state of a model. Runtime state is authoritative in memory. */
enum class ModelLoadState {
    NOT_LOADED,
    LOADING,
    LOADED,
    FAILED,
}

/**
 * Metadata about a model the user imported. The file itself stays wherever the
 * user picked it; only the SAF URI, metadata and per-model inference settings
 * are stored by LocalMind.
 */
data class LocalModel(
    val id: String,
    val displayName: String,
    val uri: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val format: ModelFormat,
    val quantization: String?,
    val loadState: ModelLoadState = ModelLoadState.NOT_LOADED,
    val backendId: String? = null,
    val validation: ModelValidation = ModelValidation.UNVERIFIED,
    val validationMessage: String? = null,
    val architecture: String? = null,
    val parameterCount: Long? = null,
    val ggufQuantization: String? = null,
    val trainedContextLength: Int? = null,
    val blockCount: Int? = null,
    val embeddingLength: Int? = null,
    val headCount: Int? = null,
    val headCountKv: Int? = null,
    val chatTemplate: String? = null,
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    val threadCount: Int = 0,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val gpuLayers: Int = 0,
) {
    /** True only for files whose GGUF header was read successfully. */
    val isUsable: Boolean get() = format == ModelFormat.GGUF && validation == ModelValidation.VALID
    val isQuantized: Boolean get() = (ggufQuantization ?: quantization) != null

    /** Quantization from the GGUF tensor types, falling back to the file name. */
    val quantizationLabel: String? get() = ggufQuantization ?: quantization

    val attentionLayerCount: Int? get() = blockCount
    val attentionHeadCount: Int? get() = headCount
    val attentionHeadCountKv: Int? get() = headCountKv

    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 4096
        const val DEFAULT_BATCH_SIZE = 512
    }
}

/**
 * Best-effort detection of a quantization tag from a file name, for example
 * `Q4_K_M`, `IQ2_XXS`, `Q8_0` or `F16`.
 */
fun detectQuantization(fileName: String): String? {
    val match = Regex("""(?i)(?<![A-Za-z0-9])(I?Q\d(?:_[A-Z0-9]+)*|F16|BF16|F32)(?![A-Za-z0-9])""")
        .find(fileName)
    return match?.value?.uppercase()
}

fun detectFormat(fileName: String): ModelFormat =
    if (fileName.endsWith(".gguf", ignoreCase = true)) ModelFormat.GGUF else ModelFormat.UNKNOWN
