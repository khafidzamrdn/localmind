package com.localmind.app.core.inference.llama

import android.content.ContentResolver
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.inference.EngineCapabilities
import com.localmind.app.core.inference.EngineDiagnostics
import com.localmind.app.core.inference.InferenceConfig
import com.localmind.app.core.inference.InferenceEngine
import com.localmind.app.core.inference.InferenceEvent
import com.localmind.app.core.inference.InferenceRequest
import com.localmind.app.core.inference.LoadPhase
import com.localmind.app.core.inference.LoadProgress
import com.localmind.app.core.inference.RuntimeProbeResult
import com.localmind.app.core.inference.TokenUsage
import com.localmind.app.core.inference.estimateTokens
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * llama.cpp backed engine.
 *
 * Loading is a two-step process:
 *
 *  1. a vocabulary-only *probe* that proves the bundled llama.cpp build knows
 *     the model architecture and quantization, then
 *  2. the real load that reads the weights, reports progress and is
 *     cancellable through the native progress callback.
 *
 * The document URI is resolved through [ModelFileAccess], which falls back to
 * an app-cache copy for providers that hand out a pipe instead of a seekable
 * file. The user's file is never modified or deleted.
 *
 * All native entry points are guarded so unloading cannot race a decode, and
 * every native exception is converted into a Kotlin [Result] failure instead of
 * reaching the UI as a crash.
 */
class LlamaCppEngine(
    contentResolver: ContentResolver,
    cacheDir: File,
) : InferenceEngine {

    override val id: String = BACKEND_ID
    override val displayName: String = "llama.cpp (CPU)"
    override val isDemo: Boolean = false

    private val lock = Mutex()

    /** Serialises native lifetime with decode; never held during a load. */
    private val nativeGuard = ReentrantLock()

    private val fileAccess = ModelFileAccess(contentResolver, cacheDir)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var loadedContextLength: Int = 0

    @Volatile
    private var lastErrorText: String? = null

    private var openedFile: OpenedModelFile? = null
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

    override suspend fun loadModel(
        model: LocalModel,
        config: InferenceConfig,
        onProgress: (LoadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        fun cancelled() = job?.isActive == false || isCancelled()

        lock.withLock {
            if (handle != 0L && loadedModelId == model.id) return@withLock Result.success(Unit)

            releaseLocked()
            lastErrorText = null

            var file: OpenedModelFile? = null
            try {
                var opened = fileAccess.open(
                    model = model,
                    onProgress = { onProgress(it) },
                    isCancelled = ::cancelled,
                )
                file = opened
                opened = probeWithCacheFallback(model, opened, onProgress, ::cancelled)
                file = opened

                val newHandle = loadNative(
                    path = opened.path,
                    config = config,
                    onProgress = onProgress,
                    cancelled = ::cancelled,
                )

                handle = newHandle
                loadedModelId = model.id
                loadedContextLength = LlamaNative.contextSize(newHandle)
                openedFile = opened
                file = null
                lastErrorText = null
                Result.success(Unit)
            } catch (error: Throwable) {
                // If load failed and file wasn't already copied to cache,
                // try copying to cache and retry once.
                if (file != null && !file.copiedToCache) {
                    try { file.close() } catch (_: Exception) {}
                    val cached = fileAccess.open(
                        model = model,
                        forceCache = true,
                        onProgress = onProgress,
                        isCancelled = ::cancelled,
                    )
                    val retryProbe = try {
                        runProbe(cached.path, onProgress, ::cancelled)
                        handle = LlamaNative.loadModel(
                            path = cached.path,
                            contextLength = config.contextLength,
                            batchSize = config.batchSize,
                            threadCount = config.resolvedThreadCount(),
                            gpuLayers = config.gpuLayers,
                            progress = LlamaNative.ProgressCallback { percent ->
                                onProgress(LoadProgress(LoadPhase.LOADING, percent))
                                !cancelled()
                            },
                        )
                        if (handle != 0L) {
                            loadedModelId = model.id
                            loadedContextLength = LlamaNative.contextSize(handle)
                            openedFile = cached
                            lastErrorText = null
                            return@withLock Result.success(Unit)
                        } else {
                            cached.close()
                            // Fall through to original error below
                        }
                    } catch (retryError: Throwable) {
                        cached.close()
                        // Fall through to original error below
                    }
                }
                file?.close()
                releaseLocked()
                Result.failure(toFailure(error, cancelled()))
            }
        }
    }

    override suspend fun probeRuntime(model: LocalModel): Result<RuntimeProbeResult> =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            fun cancelled() = job?.isActive == false
            lock.withLock {
                var file: OpenedModelFile? = null
                try {
                    val opened = fileAccess.open(model, isCancelled = ::cancelled)
                    file = opened
                    val probe = runProbe(opened.path, {}, ::cancelled)
                    lastErrorText = null
                    Result.success(
                        RuntimeProbeResult(
                            supported = true,
                            summary = probe.summary(),
                            detail = buildString {
                                append("arch=").append(probe.architecture ?: "unknown")
                                probe.parameterCount?.let { append(" · params=").append(it) }
                                probe.trainedContextLength?.let { append(" · trained ctx=").append(it) }
                                append(" · file=").append(opened.description)
                            },
                        ),
                    )
                } catch (error: Throwable) {
                    val failure = toFailure(error, cancelled())
                    lastErrorText = failure.message
                    Result.failure(failure)
                } finally {
                    file?.close()
                }
            }
        }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        val current = handle
        if (current != 0L) runCatching { LlamaNative.cancel(current) }
        lock.withLock { releaseLocked() }
    }

    override suspend fun diagnostics(model: LocalModel): EngineDiagnostics? {
        val backend = backendDescription ?: runCatching { LlamaNative.backendInfo() }.getOrNull()
            ?: return null
        val access = runCatching { fileAccess.inspect(model) }
            .getOrElse { "Not accessible: ${it.message ?: "unknown error"}" }
        val log = runCatching { LlamaNative.lastLog() }.getOrNull()
        val config = "ctx ${model.contextLength} · batch ${model.batchSize} · " +
            (if (model.threadCount > 0) "${model.threadCount} threads" else "auto threads") +
            " · gpu layers ${model.gpuLayers}"
        return EngineDiagnostics(
            backendInfo = backend,
            architecture = model.architecture,
            quantization = model.quantizationLabel,
            fileAccess = access,
            loadConfig = config,
            lastError = lastErrorText,
            nativeLog = log?.takeIf { it.isNotBlank() },
        )
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
                    val produced = nativeGuard.withLock {
                        // The model may have been unloaded between the handle
                        // read above and this call; fail cleanly instead of
                        // passing a dangling pointer to native code.
                        if (handle == 0L || handle != session) {
                            return@withLock GENERATION_STALE
                        }
                        LlamaNative.generate(
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
                    }
                    if (produced == GENERATION_STALE) {
                        trySend(
                            InferenceEvent.Failed(
                                "The model was unloaded while the response was starting.",
                                recoverable = true,
                            ),
                        )
                    } else if (produced >= 0) {
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
                                "The backend stopped before finishing the response. " +
                                    (LlamaNative.lastLog().takeIf { it.isNotBlank() }
                                        ?.let { "\n\n$it" } ?: ""),
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

    private fun runProbe(
        path: String,
        onProgress: (LoadProgress) -> Unit,
        cancelled: () -> Boolean,
    ): NativeProbe {
        val info = LlamaNative.probeModel(
            path = path,
            progress = LlamaNative.ProgressCallback { percent ->
                onProgress(LoadProgress(LoadPhase.PROBING, percent))
                !cancelled()
            },
        )
        if (cancelled()) throw ModelLoadCancelledException()
        return NativeProbe.parse(info)
    }

    /**
     * Probes [seekableFile]; when llama.cpp cannot use the descriptor even
     * though `fstat` said it was a regular file, copies to the cache once and
     * probes the copy instead.
     */
    private suspend fun probeWithCacheFallback(
        model: LocalModel,
        seekableFile: OpenedModelFile,
        onProgress: (LoadProgress) -> Unit,
        cancelled: () -> Boolean,
    ): OpenedModelFile {
        try {
            runProbe(seekableFile.path, onProgress, cancelled)
            return seekableFile
        } catch (error: Throwable) {
            if (cancelled() ||
                nativeKind() != NativeErrorKind.FILE_ACCESS ||
                seekableFile.copiedToCache
            ) {
                throw error
            }
            seekableFile.close()
            val cached = fileAccess.open(
                model = model,
                forceCache = true,
                onProgress = onProgress,
                isCancelled = cancelled,
            )
            runProbe(cached.path, onProgress, cancelled)
            return cached
        }
    }

    private fun loadNative(
        path: String,
        config: InferenceConfig,
        onProgress: (LoadProgress) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        val newHandle = LlamaNative.loadModel(
            path = path,
            contextLength = config.contextLength,
            batchSize = config.batchSize,
            threadCount = config.resolvedThreadCount(),
            gpuLayers = config.gpuLayers,
            progress = LlamaNative.ProgressCallback { percent ->
                onProgress(LoadProgress(LoadPhase.LOADING, percent))
                !cancelled()
            },
        )
        if (cancelled()) throw ModelLoadCancelledException()
        if (newHandle == 0L) {
            throw LlamaBackendException(
                NativeErrorKind.UNKNOWN,
                "llama.cpp returned an empty model handle.",
            )
        }
        return newHandle
    }

    private fun nativeKind(): NativeErrorKind = runCatching {
        NativeErrorKind.fromNative(LlamaNative.lastErrorKind())
    }.getOrDefault(NativeErrorKind.UNKNOWN)

    private fun toFailure(error: Throwable, cancelled: Boolean): Throwable {
        if (error is ModelLoadCancelledException || cancelled) {
            return ModelLoadCancelledException()
        }
        if (error is ModelAccessException) return error
        if (error is LlamaBackendException) {
            lastErrorText = error.message
            return error
        }
        val kind = nativeKind()
        lastErrorText = error.message
        return LlamaBackendException(
            kind = kind,
            message = error.message?.takeIf { it.isNotBlank() }
                ?: "llama.cpp could not load this model.",
            cause = error,
        )
    }

    private fun releaseLocked() {
        nativeGuard.withLock {
            val current = handle
            handle = 0L
            loadedModelId = null
            loadedContextLength = 0
            if (current != 0L) {
                runCatching { LlamaNative.freeModel(current) }
            }
            openedFile?.close()
            openedFile = null
        }
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

        /** Marker returned by the generate block when the handle went stale. */
        private const val GENERATION_STALE = Int.MIN_VALUE

        /**
         * Creates the engine only when the native library is packaged for this
         * device; callers fall back to the demo engine otherwise.
         */
        fun createIfAvailable(contentResolver: ContentResolver, cacheDir: File): LlamaCppEngine? =
            runCatching { LlamaCppEngine(contentResolver, cacheDir) }
                .getOrNull()
                ?.takeIf { it.probe() }
    }
}
