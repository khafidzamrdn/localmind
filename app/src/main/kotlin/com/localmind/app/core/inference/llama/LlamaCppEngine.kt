package com.localmind.app.core.inference.llama

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.inference.EngineCapabilities
import com.localmind.app.core.inference.InferenceConfig
import com.localmind.app.core.inference.InferenceEngine
import com.localmind.app.core.inference.InferenceEvent
import com.localmind.app.core.inference.InferenceRequest
import com.localmind.app.core.inference.TokenUsage
import com.localmind.app.core.inference.estimateTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * llama.cpp backed engine. Model weights are read through the persisted SAF
 * URI using `/proc/self/fd/N`, so the file is never copied into app storage.
 *
 * The instance owns at most one loaded model. [loadModel] is serialized so a
 * fast model switch cannot leak a context; [generate] streams pieces produced
 * by the native tokenizer/sampler and stops as soon as the collector is
 * cancelled.
 */
class LlamaCppEngine(
    private val contentResolver: ContentResolver,
) : InferenceEngine {

    override val id: String = BACKEND_ID
    override val displayName: String = "llama.cpp · CPU"
    override val isDemo: Boolean = false

    private val lock = Mutex()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var loadedContextLength: Int = 0

    private var descriptor: ParcelFileDescriptor? = null
    private var backendDescription: String? = null

    /** Safe to call; returns false when the native library is missing for this ABI. */
    fun probe(): Boolean = runCatching {
        backendDescription = LlamaNative.backendInfo()
        true
    }.getOrDefault(false)

    fun systemInfo(): String? = backendDescription

    override fun capabilities() = EngineCapabilities(
        streaming = true,
        nativeToolCalling = false,
        gpuAcceleration = false,
        configurableContext = true,
    )

    override suspend fun loadModel(model: LocalModel, config: InferenceConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (handle != 0L && loadedModelId == model.id) return@withLock Result.success(Unit)

                releaseLocked()
                try {
                    val pfd = contentResolver.openFileDescriptor(Uri.parse(model.uri), "r")
                        ?: return@withLock Result.failure(
                            IllegalStateException(
                                "Android could not open this document. Re-import the model to restore access.",
                            ),
                        )
                    val fdPath = "/proc/self/fd/${pfd.fd}"
                    val newHandle = LlamaNative.loadModel(
                        fdPath = fdPath,
                        contextLength = config.contextLength,
                        batchSize = config.batchSize,
                        threadCount = config.resolvedThreadCount(),
                        gpuLayers = config.gpuLayers,
                    )
                    if (newHandle == 0L) {
                        pfd.close()
                        return@withLock Result.failure(
                            IllegalStateException("llama.cpp returned an empty model handle."),
                        )
                    }
                    handle = newHandle
                    descriptor = pfd
                    loadedModelId = model.id
                    loadedContextLength = LlamaNative.contextSize(newHandle)
                    Result.success(Unit)
                } catch (error: Throwable) {
                    releaseLocked()
                    Result.failure(
                        IllegalStateException(
                            error.message?.takeIf { it.isNotBlank() }
                                ?: "llama.cpp could not load this model.",
                            error,
                        ),
                    )
                }
            }
        }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        lock.withLock { releaseLocked() }
    }

    override fun generate(request: InferenceRequest): Flow<InferenceEvent> {
        val session = handle
        if (session == 0L) {
            return flowOf(
                InferenceEvent.Failed(
                    message = "No model is loaded. Select a model in Models and try again.",
                    recoverable = false,
                ),
            )
        }

        val roles = mutableListOf<String>()
        val contents = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) {
            roles += "system"
            contents += request.systemPrompt
        }
        request.messages.forEach { message ->
            // Empty assistant rows appear when a reply was stopped before the
            // first token arrived; they carry no information for the model.
            if (message.role == MessageRole.ASSISTANT && message.content.isBlank()) return@forEach
            roles += message.role.jniName()
            contents += message.content
        }
        if (contents.isEmpty()) {
            return flowOf(
                InferenceEvent.Failed("There is no message to send to the model.", recoverable = false),
            )
        }

        val promptTokens = estimateTokens(request.systemPrompt) +
            request.messages.sumOf { estimateTokens(it.content) }
        val params = request.params

        return callbackFlow {
            val callback = LlamaNative.TokenCallback { bytes ->
                // A failed send means the collector cancelled or the channel
                // closed, which is the signal the native loop uses to stop.
                trySend(InferenceEvent.Token(String(bytes, Charsets.UTF_8))).isSuccess
            }

            launch(Dispatchers.Default) {
                try {
                    if (handle != session) {
                        trySend(
                            InferenceEvent.Failed(
                                "The model was unloaded while the response was starting.",
                                recoverable = true,
                            ),
                        )
                        return@launch
                    }
                    val produced = LlamaNative.generate(
                        handle = session,
                        roles = roles.toTypedArray(),
                        contents = contents.toTypedArray(),
                        maxTokens = params.maxTokens,
                        temperature = params.temperature.toFloat(),
                        topP = params.topP.toFloat(),
                        topK = params.topK,
                        repeatPenalty = params.repeatPenalty.toFloat(),
                        callback = callback,
                    )
                    if (produced >= 0) {
                        trySend(
                            InferenceEvent.Completed(
                                TokenUsage(
                                    promptTokens = promptTokens,
                                    generatedTokens = produced,
                                ),
                            ),
                        )
                    } else if (produced == -1) {
                        trySend(
                            InferenceEvent.Failed(
                                "The backend stopped before finishing the response.",
                                recoverable = true,
                            ),
                        )
                    }
                    // -2 means the collector cancelled; no further events.
                } catch (error: Throwable) {
                    trySend(
                        InferenceEvent.Failed(
                            message = error.message?.takeIf { it.isNotBlank() }
                                ?: "llama.cpp failed while generating a response.",
                            recoverable = true,
                            cause = error,
                        ),
                    )
                } finally {
                    close()
                }
            }

            awaitClose {
                // Stop prefill/decode promptly instead of waiting for the next
                // token; the flag is checked inside llama.cpp's compute loop.
                LlamaNative.cancel(session)
            }
        }.buffer(Channel.UNLIMITED)
    }

    private fun releaseLocked() {
        val current = handle
        handle = 0L
        loadedModelId = null
        loadedContextLength = 0
        if (current != 0L) {
            runCatching { LlamaNative.freeModel(current) }
        }
        runCatching { descriptor?.close() }
        descriptor = null
    }

    val loadedContextSize: Int get() = loadedContextLength

    private fun MessageRole.jniName(): String = when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.SYSTEM -> "system"
        MessageRole.TOOL -> "tool"
    }

    companion object {
        const val BACKEND_ID = "llama.cpp"

        /**
         * Creates the engine only when the native library is packaged for this
         * device; callers fall back to the demo engine otherwise.
         */
        fun createIfAvailable(contentResolver: ContentResolver): LlamaCppEngine? =
            runCatching { LlamaCppEngine(contentResolver) }
                .getOrNull()
                ?.takeIf { it.probe() }
    }
}
