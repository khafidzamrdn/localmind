package com.localmind.app.core.inference.llama

import com.localmind.app.core.inference.InferenceCancelledException
import java.io.IOException

/** Thrown when the user or the app cancelled a model load. */
class ModelLoadCancelledException : InferenceCancelledException()

/** Thrown when the document URI cannot be opened or read. */
class ModelAccessException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A failure reported by the native backend. [kind] classifies the reason using
 * the llama.cpp log that was captured during the call, and [message] already
 * contains that log tail.
 */
class LlamaBackendException(
    val kind: NativeErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
