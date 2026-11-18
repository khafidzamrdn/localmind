package com.localmind.app.core.inference.llama

/**
 * Low-level JNI surface for the bundled llama.cpp build. Only [LlamaCppEngine]
 * should touch this object; loading it throws when the native library is not
 * packaged for the device ABI, which is how the app detects that it must fall
 * back to the demo engine.
 *
 * Every native call may throw [IllegalStateException] whose message contains
 * the captured llama.cpp log, so callers can show the real failure reason.
 */
internal object LlamaNative {

    /** Receives UTF-8 pieces; returning false stops generation. */
    fun interface TokenCallback {
        fun onToken(bytes: ByteArray): Boolean
    }

    /**
     * Receives a 0..100 progress value while a model is read. Returning false
     * aborts the load inside llama.cpp (used for cancellation).
     */
    fun interface ProgressCallback {
        fun onProgress(percent: Int): Boolean
    }

    init {
        System.loadLibrary("localmind_llama")
    }

    /** llama.cpp version, system info (CPU features), backend/device counts. */
    external fun backendInfo(): String

    /** Tail of the llama.cpp/ggml log captured since the last clear. */
    external fun lastLog(): String

    external fun clearLog()

    /** Last classified failure, see [NativeErrorKind]. */
    external fun lastErrorKind(): Int

    /**
     * Loads the vocabulary and tensor descriptors only. This proves that this
     * llama.cpp build understands the architecture and quantization without
     * allocating the weights. Returns `arch=`, `desc=`, `params=` and
     * `ctx_train=` lines.
     */
    external fun probeModel(path: String, progress: ProgressCallback?): String

    external fun loadModel(
        path: String,
        contextLength: Int,
        batchSize: Int,
        threadCount: Int,
        gpuLayers: Int,
        progress: ProgressCallback?,
    ): Long

    external fun freeModel(handle: Long)

    external fun contextSize(handle: Long): Int

    /** Requests that an in-flight generation stop as soon as possible. */
    external fun cancel(handle: Long)

    /**
     * Formats [roles]/[contents] with the model's embedded chat template,
     * tokenizes and generates. Returns the number of tokens produced, or -1
     * when the backend reported an error (a Java exception is thrown first).
     * -2 means the generation was cancelled.
     */
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenCallback,
    ): Int
}

/** Mirrors the `ErrorKind` enum in `localmind_llama.cpp`. */
enum class NativeErrorKind {
    UNKNOWN,
    UNSUPPORTED_ARCHITECTURE,
    FILE_ACCESS,
    OUT_OF_MEMORY,
    CANCELLED;

    companion object {
        fun fromNative(value: Int): NativeErrorKind = entries.getOrNull(value) ?: UNKNOWN
    }
}

internal data class NativeProbe(
    val architecture: String?,
    val description: String?,
    val parameterCount: Long?,
    val trainedContextLength: Int?,
) {
    fun summary(): String = buildString {
        append(description?.takeIf { it.isNotBlank() } ?: "llama.cpp model")
        architecture?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }

    companion object {
        /** Parses the `key=value` lines returned by [LlamaNative.probeModel]. */
        fun parse(info: String): NativeProbe {
            var arch: String? = null
            var desc: String? = null
            var params: Long? = null
            var ctx: Int? = null
            info.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                when (key) {
                    "arch" -> arch = value.takeIf { it.isNotBlank() }
                    "desc" -> desc = value.takeIf { it.isNotBlank() }
                    "params" -> params = value.toLongOrNull()
                    "ctx_train" -> ctx = value.toIntOrNull()?.takeIf { it > 0 }
                }
            }
            return NativeProbe(arch, desc, params, ctx)
        }
    }
}
