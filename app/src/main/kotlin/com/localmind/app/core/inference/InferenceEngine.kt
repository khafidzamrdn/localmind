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

    /** Loads [model] into memory. Safe to call with the same model twice. */
    suspend fun loadModel(model: LocalModel): Result<Unit>

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
)

data class TokenUsage(
    val promptTokens: Int,
    val generatedTokens: Int,
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
