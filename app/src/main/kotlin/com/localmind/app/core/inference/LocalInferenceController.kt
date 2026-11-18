package com.localmind.app.core.inference

import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelLoadState
import com.localmind.app.core.domain.model.ModelValidation
import com.localmind.app.core.domain.model.RuntimeDefaults
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime ownership of the single loaded model. */
sealed interface ModelRuntimeState {
    data object Unloaded : ModelRuntimeState

    data class Loading(
        val modelId: String,
        val phase: LoadPhase = LoadPhase.PREPARING,
        val percent: Int = 0,
    ) : ModelRuntimeState

    data class Loaded(
        val modelId: String,
        val backendId: String,
        val contextLength: Int,
        val demo: Boolean,
    ) : ModelRuntimeState

    data class Failed(val modelId: String?, val message: String) : ModelRuntimeState
}

/**
 * Serializes load/unload across the app, keeps the Room load state in sync and
 * exposes the authoritative runtime state to Chat and Models. All state changes
 * happen inside [Mutex] so a fast model switch cannot leave two models loaded.
 *
 * A load can be cancelled through [cancelLoad]; the flag is polled by the
 * native progress callback, so even a blocking llama.cpp load stops promptly.
 */
class LocalInferenceController(
    private val engine: InferenceEngine,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val mutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.Unloaded)
    val state: StateFlow<ModelRuntimeState> = _state.asStateFlow()

    val backendId: String get() = engine.id
    val backendName: String get() = engine.displayName
    val isDemo: Boolean get() = engine.isDemo
    val supportsRuntimeProbe: Boolean get() = !engine.isDemo

    fun capabilities(): EngineCapabilities = engine.capabilities()

    /** Model currently loaded in memory, or null. */
    fun loadedModelId(): String? = (_state.value as? ModelRuntimeState.Loaded)?.modelId

    /** Asks an in-flight load to stop; the load reports "cancelled" as a failure. */
    fun cancelLoad() {
        cancelRequested.set(true)
    }

    suspend fun ensureLoaded(modelId: String): Result<Unit> {
        if (engine.isDemo) return Result.success(Unit)
        val loaded = _state.value as? ModelRuntimeState.Loaded
        if (loaded?.modelId == modelId) return Result.success(Unit)
        return load(modelId)
    }

    suspend fun load(modelId: String): Result<Unit> = mutex.withLock {
        if (engine.isDemo) {
            return Result.failure(
                IllegalStateException("The demo engine does not read model files."),
            )
        }

        val model = modelRepository.getModel(modelId)
            ?: return Result.failure(IllegalArgumentException("Model no longer exists in the library."))
        if (!model.isUsable) {
            return Result.failure(IllegalArgumentException(modelLoadBlocker(model)))
        }

        val current = _state.value as? ModelRuntimeState.Loaded
        if (current?.modelId == modelId) return Result.success(Unit)

        if (current != null) {
            runCatching { engine.unload() }
            modelRepository.updateLoadState(current.modelId, ModelLoadState.NOT_LOADED)
            _state.value = ModelRuntimeState.Unloaded
        }

        cancelRequested.set(false)
        _state.value = ModelRuntimeState.Loading(modelId)
        modelRepository.updateLoadState(modelId, ModelLoadState.LOADING)

        val settings = settingsRepository.current()
        val config = resolveConfig(model, settings.runtime)

        val result = try {
            engine.loadModel(
                model = model,
                config = config,
                onProgress = { progress ->
                    if (!cancelRequested.get()) {
                        _state.value = ModelRuntimeState.Loading(
                            modelId = modelId,
                            phase = progress.phase,
                            percent = progress.percent.coerceIn(0, 100),
                        )
                    }
                },
                isCancelled = { cancelRequested.get() },
            )
        } catch (cancelled: CancellationException) {
            // The caller's scope went away mid-load; still reset the shared
            // state so the app does not stay stuck on "Loading".
            withContext(NonCancellable) {
                modelRepository.updateLoadState(modelId, ModelLoadState.NOT_LOADED)
                _state.value = ModelRuntimeState.Unloaded
            }
            return Result.failure(InferenceCancelledException())
        }

        return result.fold(
            onSuccess = {
                val description = buildString {
                    append(engine.displayName)
                    model.architecture?.let { append(" · ").append(it) }
                    model.quantizationLabel?.let { append(" · ").append(it) }
                    append(" · ").append(config.contextLength).append(" ctx")
                }
                modelRepository.updateLoadState(modelId, ModelLoadState.LOADED, engine.id)
                modelRepository.updateRuntimeValidation(modelId, true, description, null)
                _state.value = ModelRuntimeState.Loaded(
                    modelId = modelId,
                    backendId = engine.id,
                    contextLength = config.contextLength,
                    demo = false,
                )
                Result.success(Unit)
            },
            onFailure = { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "The backend could not load this model."
                if (error is InferenceCancelledException) {
                    modelRepository.updateLoadState(modelId, ModelLoadState.NOT_LOADED, engine.id)
                    _state.value = ModelRuntimeState.Unloaded
                    Result.failure(InferenceCancelledException(message))
                } else {
                    modelRepository.updateLoadState(modelId, ModelLoadState.FAILED, engine.id)
                    modelRepository.updateRuntimeValidation(modelId, false, null, message)
                    _state.value = ModelRuntimeState.Failed(modelId, message)
                    Result.failure(IllegalStateException(message, error))
                }
            },
        )
    }

    /**
     * Runs the vocabulary-only compatibility probe for [modelId] and stores the
     * result. Does not load the weights, so it is safe to run on any model.
     */
    suspend fun probeRuntime(modelId: String): Result<RuntimeProbeResult> = mutex.withLock {
        val model = modelRepository.getModel(modelId)
            ?: return Result.failure(IllegalArgumentException("Model no longer exists in the library."))
        if (engine.isDemo) {
            return Result.failure(
                IllegalStateException("The demo engine cannot inspect GGUF files."),
            )
        }
        val result = engine.probeRuntime(model)
        result.fold(
            onSuccess = { probe ->
                modelRepository.updateRuntimeValidation(modelId, true, probe.summary, null)
                Result.success(probe)
            },
            onFailure = { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "The backend could not inspect this model."
                modelRepository.updateRuntimeValidation(modelId, false, null, message)
                Result.failure(IllegalStateException(message, error))
            },
        )
    }

    suspend fun diagnostics(modelId: String): EngineDiagnostics? =
        modelRepository.getModel(modelId)?.let { engine.diagnostics(it) }

    suspend fun unload() = mutex.withLock {
        // Make sure a load that is still running stops before we tear down.
        cancelRequested.set(true)
        val current = _state.value as? ModelRuntimeState.Loaded
        runCatching { engine.unload() }
        if (current != null) {
            modelRepository.updateLoadState(current.modelId, ModelLoadState.NOT_LOADED)
        }
        _state.value = ModelRuntimeState.Unloaded
    }

    private fun resolveConfig(
        model: LocalModel,
        defaults: RuntimeDefaults,
    ): InferenceConfig {
        val lowMemory = defaults.lowMemory
        val requestedContext = model.contextLength.coerceAtLeast(512)
        val budgetedContext = if (lowMemory) requestedContext.coerceAtMost(LOW_MEMORY_CONTEXT) else requestedContext
        val cappedContext = model.trainedContextLength
            ?.takeIf { it > 0 }
            ?.let { trained -> budgetedContext.coerceAtMost(trained) }
            ?: budgetedContext
        val requestedThreads = model.threadCount.takeIf { it > 0 } ?: defaults.threadCount
        val resolvedThreads = if (requestedThreads > 0) {
            requestedThreads
        } else {
            InferenceConfig.defaultThreadCount()
        }
        val requestedBatch = model.batchSize.takeIf { it > 0 } ?: defaults.batchSize
        return InferenceConfig(
            threadCount = if (lowMemory) resolvedThreads.coerceAtMost(LOW_MEMORY_THREADS) else resolvedThreads,
            contextLength = cappedContext,
            batchSize = if (lowMemory) requestedBatch.coerceAtMost(LOW_MEMORY_BATCH) else requestedBatch,
            gpuLayers = if (engine.capabilities().gpuAcceleration) model.gpuLayers else 0,
        )
    }

    private fun modelLoadBlocker(model: LocalModel): String = when (model.validation) {
        ModelValidation.HEADER_VALID -> "This model cannot be loaded."
        ModelValidation.UNVERIFIED ->
            "Validate the GGUF header in Models before loading this file."
        ModelValidation.INVALID ->
            model.validationMessage ?: "The GGUF header is invalid."
        ModelValidation.MISSING ->
            model.validationMessage ?: "The model file is no longer accessible."
    }

    private companion object {
        const val LOW_MEMORY_CONTEXT = 2048
        const val LOW_MEMORY_BATCH = 256
        const val LOW_MEMORY_THREADS = 4
    }
}
