package com.localmind.app.feature.models

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelFormat
import com.localmind.app.core.domain.model.ModelLoadState
import com.localmind.app.core.domain.model.ModelValidation
import com.localmind.app.core.domain.model.detectFormat
import com.localmind.app.core.domain.model.detectQuantization
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import com.localmind.app.core.gguf.GgufModelValidator
import com.localmind.app.core.inference.LocalInferenceController
import com.localmind.app.core.inference.ModelRuntimeState
import com.localmind.app.core.inference.ResourceEstimate
import com.localmind.app.core.inference.ResourceEstimator
import com.localmind.app.core.inference.ResourceRisk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoadRequest(
    val model: LocalModel,
    val estimate: ResourceEstimate,
)

class ModelsViewModel(
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val contentResolver: ContentResolver,
    private val validator: GgufModelValidator,
    private val estimator: ResourceEstimator,
    private val controller: LocalInferenceController,
) : ViewModel() {

    val models: StateFlow<List<LocalModel>> = modelRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val runtimeState: StateFlow<ModelRuntimeState> = controller.state

    val backendName: String = controller.backendName
    val backendIsDemo: Boolean = controller.isDemo
    val gpuSupported: Boolean = controller.capabilities().gpuAcceleration
    val maxThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _validatingModelIds = MutableStateFlow<Set<String>>(emptySet())
    val validatingModelIds: StateFlow<Set<String>> = _validatingModelIds.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loadRequest = MutableStateFlow<LoadRequest?>(null)
    val loadRequest: StateFlow<LoadRequest?> = _loadRequest.asStateFlow()

    init {
        revalidateUnknownModels()
    }

    /** Re-reads headers for rows imported before Milestone 2. */
    private fun revalidateUnknownModels() {
        viewModelScope.launch {
            val pending = modelRepository.observeModels().first()
                .filter { it.validation == ModelValidation.UNVERIFIED }
            pending.forEach { model ->
                _validatingModelIds.value += model.id
                val validated = validator.validate(model)
                modelRepository.updateModel(validated)
                _validatingModelIds.value -= model.id
            }
        }
    }

    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty() || _isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            var added = 0
            var duplicates = 0
            var invalid = 0
            var failed = 0

            val settingsSnapshot = settingsRepository.current()

            uris.forEach { uri ->
                try {
                    withContext(Dispatchers.IO) {
                        val existing = modelRepository.findByUri(uri.toString())
                        if (existing != null) {
                            duplicates++
                            return@withContext
                        }
                        persistReadPermission(uri)
                        val metadata = readMetadata(uri)
                        val displayName = metadata?.first ?: uri.lastPathSegment ?: "model.gguf"
                        val size = metadata?.second ?: 0L
                        val format = detectFormat(displayName)
                        val initial = LocalModel(
                            id = UUID.randomUUID().toString(),
                            displayName = displayName,
                            uri = uri.toString(),
                            sizeBytes = size,
                            importedAt = System.currentTimeMillis(),
                            format = format,
                            quantization = detectQuantization(displayName),
                            loadState = ModelLoadState.NOT_LOADED,
                            validation = ModelValidation.UNVERIFIED,
                            threadCount = settingsSnapshot.runtime.threadCount,
                            batchSize = settingsSnapshot.runtime.batchSize,
                            gpuLayers = if (gpuSupported) {
                                if (settingsSnapshot.runtime.gpuEnabled) 99 else 0
                            } else {
                                0
                            },
                        )
                        val validated = if (format == ModelFormat.GGUF) {
                            validator.validate(initial)
                        } else {
                            initial.copy(
                                validation = ModelValidation.INVALID,
                                validationMessage = "Only .gguf files are supported.",
                            )
                        }
                        modelRepository.addModel(validated)
                        added++
                        if (validated.validation != ModelValidation.VALID) invalid++
                    }
                } catch (error: Exception) {
                    failed++
                }
            }

            _message.value = buildString {
                append("$added file${if (added == 1) "" else "s"} processed")
                if (duplicates > 0) append(" · $duplicates already in library")
                if (invalid > 0) append(" · $invalid not loadable")
                if (failed > 0) append(" · $failed could not be read")
            }
            _isImporting.value = false
        }
    }

    fun revalidate(modelId: String) {
        viewModelScope.launch {
            val model = modelRepository.getModel(modelId) ?: return@launch
            _validatingModelIds.value += modelId
            val validated = validator.validate(model)
            modelRepository.updateModel(validated)
            _validatingModelIds.value -= modelId
            _message.value = if (validated.validation == ModelValidation.VALID) {
                "GGUF header verified."
            } else {
                validated.validationMessage ?: "The model could not be validated."
            }
        }
    }

    /** Called by the Load button; asks for confirmation when memory is tight. */
    fun requestLoad(modelId: String) {
        val model = models.value.firstOrNull { it.id == modelId } ?: return
        if (backendIsDemo) {
            _message.value = "The native backend is not available on this device/build, so files cannot be loaded."
            return
        }
        if (!model.isUsable) {
            _message.value = model.validationMessage ?: "Validate this model before loading it."
            return
        }
        val estimate = estimator.estimate(model, model.contextLength, model.batchSize)
        if (estimate.risk == ResourceRisk.COMFORTABLE) {
            load(model)
        } else {
            _loadRequest.value = LoadRequest(model, estimate)
        }
    }

    fun confirmLoad() {
        val request = _loadRequest.value ?: return
        _loadRequest.value = null
        load(request.model)
    }

    fun dismissLoadRequest() {
        _loadRequest.value = null
    }

    private fun load(model: LocalModel) {
        viewModelScope.launch {
            controller.load(model.id).fold(
                onSuccess = {
                    _message.value = "${model.displayName} loaded on ${controller.backendName}."
                },
                onFailure = { error ->
                    _message.value = error.message ?: "The model failed to load."
                },
            )
        }
    }

    fun unload() {
        viewModelScope.launch {
            controller.unload()
            _message.value = "Model unloaded and memory released."
        }
    }

    fun configure(
        modelId: String,
        contextLength: Int,
        threadCount: Int,
        batchSize: Int,
        gpuLayers: Int,
    ) {
        viewModelScope.launch {
            val model = modelRepository.getModel(modelId) ?: return@launch
            val cap = model.trainedContextLength?.takeIf { it > 0 }
            val clampedContext = if (cap != null) contextLength.coerceIn(512, cap) else contextLength.coerceAtLeast(512)
            val clampedThreads = threadCount.coerceIn(0, 16)
            val clampedBatch = batchSize.coerceIn(64, 2048)
            val clampedGpu = if (gpuSupported) gpuLayers.coerceIn(0, 999) else 0
            modelRepository.updateConfig(
                modelId = modelId,
                contextLength = clampedContext,
                threadCount = clampedThreads,
                batchSize = clampedBatch,
                gpuLayers = clampedGpu,
            )
            val loaded = (controller.state.value as? ModelRuntimeState.Loaded)?.modelId == modelId
            _message.value = if (loaded) {
                "Configuration saved. Unload and load again to apply it."
            } else {
                "Configuration saved."
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            val model = modelRepository.getModel(modelId) ?: return@launch
            if (!model.isUsable) {
                _message.value = model.validationMessage ?: "This file is not a loadable GGUF model."
                return@launch
            }
            settingsRepository.setActiveModel(modelId)
            _message.value = "${model.displayName} is now the selected model."
        }
    }

    fun clearDefaultModel() {
        viewModelScope.launch { settingsRepository.setActiveModel(null) }
    }

    fun removeModel(modelId: String) {
        viewModelScope.launch {
            if ((controller.state.value as? ModelRuntimeState.Loaded)?.modelId == modelId) {
                controller.unload()
            }
            modelRepository.removeModel(modelId)
            if (settings.value.activeModelId == modelId) {
                settingsRepository.setActiveModel(null)
            }
            _message.value = "Removed from library. The file on disk was not deleted."
        }
    }

    fun estimateFor(model: LocalModel): ResourceEstimate =
        estimator.estimate(model, model.contextLength, model.batchSize)

    fun clearMessage() {
        _message.value = null
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun readMetadata(uri: Uri): Pair<String, Long>? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                if (name != null) return name to size
            }
        }
        return null
    }
}
