package com.localmind.app.core.inference

import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelLoadState
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime ownership of the single loaded model. */
sealed interface ModelRuntimeState {
    data object Unloaded : ModelRuntimeState

    data class Loading(val modelId: String) : ModelRuntimeState

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
 */
class LocalInferenceController(
    private val engine: InferenceEngine,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.Unloaded)
    val state: StateFlow<ModelRuntimeState> = _state.asStateFlow()

    val backendId: String get() = engine.id
    val backendName: String get() = engine.displayName
    val isDemo: Boolean get() = engine.isDemo

    fun capabilities(): EngineCapabilities = engine.capabilities()

    /** Model currently loaded in memory, or null. */
    fun loadedModelId(): String? = (_state.value as? ModelRuntimeState.Loaded)?.modelId

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

        _state.value = ModelRuntimeState.Loading(modelId)
        modelRepository.updateLoadState(modelId, ModelLoadState.LOADING)

        val settings = settingsRepository.current()
        val config = resolveConfig(model, settings.runtime)

        val result = runCatching { engine.loadModel(model, config) }
            .getOrElse { Result.failure(it) }

        return result.fold(
            onSuccess = {
                modelRepository.updateLoadState(modelId, ModelLoadState.LOADED, engine.id)
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
                modelRepository.updateLoadState(modelId, ModelLoadState.FAILED, engine.id)
                _state.value = ModelRuntimeState.Failed(modelId, message)
                Result.failure(IllegalStateException(message, error))
            },
        )
    }

    suspend fun unload() = mutex.withLock {
        val current = _state.value as? ModelRuntimeState.Loaded
        runCatching { engine.unload() }
        if (current != null) {
            modelRepository.updateLoadState(current.modelId, ModelLoadState.NOT_LOADED)
        }
        _state.value = ModelRuntimeState.Unloaded
    }

    private fun resolveConfig(
        model: LocalModel,
        defaults: com.localmind.app.core.domain.model.RuntimeDefaults,
    ): InferenceConfig {
        val requestedContext = model.contextLength.coerceAtLeast(512)
        val cappedContext = model.trainedContextLength
            ?.takeIf { it > 0 }
            ?.let { trained -> requestedContext.coerceAtMost(trained) }
            ?: requestedContext
        return InferenceConfig(
            threadCount = model.threadCount.takeIf { it > 0 } ?: defaults.threadCount,
            contextLength = cappedContext,
            batchSize = model.batchSize.takeIf { it > 0 } ?: defaults.batchSize,
            gpuLayers = if (engine.capabilities().gpuAcceleration) model.gpuLayers else 0,
        )
    }

    private fun modelLoadBlocker(model: LocalModel): String = when (model.validation) {
        com.localmind.app.core.domain.model.ModelValidation.VALID -> "This model cannot be loaded."
        com.localmind.app.core.domain.model.ModelValidation.UNVERIFIED ->
            "Validate the GGUF header in Models before loading this file."
        com.localmind.app.core.domain.model.ModelValidation.INVALID ->
            model.validationMessage ?: "The GGUF header is invalid."
        com.localmind.app.core.domain.model.ModelValidation.MISSING ->
            model.validationMessage ?: "The model file is no longer accessible."
    }
}
