package com.localmind.app.core.inference

import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.GenerationParams
import com.localmind.app.core.domain.model.LocalModel
import kotlinx.coroutines.flow.Flow

/**
 * Backend-agnostic contract for text generation. Implementations may wrap
 * llama.cpp, a remote API, or (for milestone 1) a deterministic demo engine.
 *
 * The interface intentionally knows nothing about Android, Compose or Room so
 * that additional backends can be plugged in without touching the UI layer.
 */
interface InferenceEngine {
    val id: String
    val displayName: String

    /** True when output is simulated and must not be presented as model output. */
    val isDemo: Boolean

    fun capabilities(): EngineCapabilities

    /**
     * Loads [model] into memory using [config]. Implementations must validate
     * that they can actually run the file and return a failure with an
     * actionable message otherwise. Safe to call with the same model twice.
     *
     * [onProgress] receives coarse phases and percentages. [isCancelled] is
     * polled by native backends (from their progress callback) so a blocking
     * load can be aborted without waiting for the coroutine to return.
     */
    suspend fun loadModel(
        model: LocalModel,
        config: InferenceConfig,
        onProgress: (LoadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<Unit>

    /**
     * Verifies, without keeping the weights in memory, that this backend can
     * actually run [model]. Used for the "Runtime compatible" status.
     */
    suspend fun probeRuntime(model: LocalModel): Result<RuntimeProbeResult> =
        Result.failure(UnsupportedOperationException("This backend cannot probe model files."))

    /** Snapshot of backend and file-access details for the diagnostics panel. */
    suspend fun diagnostics(model: LocalModel): EngineDiagnostics? = null

    /** Releases backend resources. Must be idempotent. */
    suspend fun unload()

    /**
     * Streams generation events. Cancelling the collecting coroutine must stop
     * token production promptly; implementations should also call
     * [kotlinx.coroutines.currentCoroutineContext] cancellation checks.
     */
    fun generate(request: InferenceRequest): Flow<InferenceEvent>
}

data class EngineCapabilities(
    val streaming: Boolean,
    val nativeToolCalling: Boolean,
    val gpuAcceleration: Boolean,
    val configurableContext: Boolean,
)

data class InferenceRequest(
    /** Conversation history in chronological order, excluding the system prompt. */
    val messages: List<ChatMessage>,
    val systemPrompt: String,
    val params: GenerationParams,
    val model: LocalModel?,
    val config: InferenceConfig = InferenceConfig(),
)

/** Thrown when a model load was cancelled by the user or by the app. */
open class InferenceCancelledException(
    message: String = "The model load was cancelled.",
) : Exception(message)

data class TokenUsage(
    val promptTokens: Int,
    val generatedTokens: Int,
)

/** Phases reported while a model is being prepared and loaded. */
enum class LoadPhase {
    /** Resolving the document URI and checking whether the file is seekable. */
    PREPARING,

    /** Copying a non-seekable document into the app cache. */
    COPYING,

    /** Vocabulary/architecture probe (no weights allocated). */
    PROBING,

    /** Reading weights and creating the llama.cpp context. */
    LOADING,
}

data class LoadProgress(val phase: LoadPhase, val percent: Int = 0)

/** Result of a runtime-compatibility probe (loads the vocabulary only). */
data class RuntimeProbeResult(
    val supported: Boolean,
    val summary: String,
    val detail: String? = null,
)

/** Everything the diagnostics panel shows about one model/backend pair. */
data class EngineDiagnostics(
    val backendInfo: String,
    val architecture: String?,
    val quantization: String?,
    val fileAccess: String,
    val loadConfig: String,
    val lastError: String?,
    val nativeLog: String?,
)


sealed interface InferenceEvent {
    data class Token(val text: String) : InferenceEvent

    data class Completed(val usage: TokenUsage) : InferenceEvent

    data class Failed(
        val message: String,
        val recoverable: Boolean = true,
        val cause: Throwable? = null,
    ) : InferenceEvent
}

/** Rough token estimate used for context indicators and demo usage stats. */
fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
