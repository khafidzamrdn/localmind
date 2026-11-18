package com.localmind.app.core.inference.llama

/**
 * Low-level JNI surface for the bundled llama.cpp build. Only [LlamaCppEngine]
 * should touch this object; loading it throws when the native library is not
 * packaged for the device ABI, which is how the app detects that it must fall
 * back to the demo engine.
 */
internal object LlamaNative {

    /** Receives UTF-8 pieces; returning false stops generation. */
    fun interface TokenCallback {
        fun onToken(bytes: ByteArray): Boolean
    }

    init {
        System.loadLibrary("localmind_llama")
    }

    external fun backendInfo(): String

    external fun loadModel(
        fdPath: String,
        contextLength: Int,
        batchSize: Int,
        threadCount: Int,
        gpuLayers: Int,
    ): Long

    external fun freeModel(handle: Long)

    external fun contextSize(handle: Long): Int

    /** Requests that an in-flight generation stop as soon as possible. */
    external fun cancel(handle: Long)

    /**
     * Formats [roles]/[contents] with the model's embedded chat template,
     * tokenizes and generates. Returns the number of tokens produced, or -1
     * when the backend reported an error (a Java exception is thrown first).
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
