package com.localmind.app.core.domain.model

/** Container format detected from the file name. */
enum class ModelFormat {
    GGUF,
    UNKNOWN,
}

/** Persisted load state of a model. Milestone 1 only produces NOT_LOADED. */
enum class ModelLoadState {
    NOT_LOADED,
    LOADING,
    LOADED,
    FAILED,
}

/**
 * Metadata about a model the user imported. The file itself stays wherever the
 * user picked it; only the SAF URI and metadata are stored by LocalMind.
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
) {
    val isUsable: Boolean get() = format == ModelFormat.GGUF
    val isQuantized: Boolean get() = quantization != null
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
