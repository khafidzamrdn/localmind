package com.localmind.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.GenerationParams
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.RuntimeDefaults
import com.localmind.app.core.domain.model.ThemeMode
import com.localmind.app.core.domain.repository.ConversationRepository
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import com.localmind.app.core.inference.DeviceCapabilities
import com.localmind.app.core.inference.DeviceMemory
import com.localmind.app.core.inference.LocalInferenceController
import com.localmind.app.core.inference.ResourceEstimator
import com.localmind.app.core.i18n.AppLanguage
import com.localmind.app.core.termux.TermuxBridge
import com.localmind.app.core.termux.TermuxStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    modelRepository: ModelRepository,
    private val databaseFile: File,
    private val resourceEstimator: ResourceEstimator,
    private val controller: LocalInferenceController,
    private val termuxBridge: TermuxBridge,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val models: StateFlow<List<LocalModel>> = modelRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private val _deviceMemory = MutableStateFlow(resourceEstimator.deviceMemory())
    val deviceMemory: StateFlow<DeviceMemory> = _deviceMemory.asStateFlow()

    private val _termuxStatus = MutableStateFlow(termuxBridge.status())
    val termuxStatus: StateFlow<TermuxStatus> = _termuxStatus.asStateFlow()

    val backendName: String = controller.backendName
    val backendIsDemo: Boolean = controller.isDemo
    val gpuSupported: Boolean = controller.capabilities().gpuAcceleration

    val capabilities: DeviceCapabilities = DeviceCapabilities(
        cpuCores = Runtime.getRuntime().availableProcessors(),
        totalMemoryBytes = _deviceMemory.value.totalBytes,
        availableMemoryBytes = _deviceMemory.value.availableBytes,
        lowRamDevice = _deviceMemory.value.lowRamDevice,
        gpuAcceleration = gpuSupported,
        backendName = backendName,
        backendIsDemo = backendIsDemo,
    )

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        _deviceMemory.value = resourceEstimator.deviceMemory()
        _termuxStatus.value = termuxBridge.status()
        viewModelScope.launch {
            _storageBytes.value = withContext(Dispatchers.IO) {
                listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
                    .filter { it.exists() }
                    .sumOf { it.length() }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    fun setSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsRepository.setSystemPrompt(prompt) }
    }

    fun resetSystemPrompt() {
        viewModelScope.launch { settingsRepository.setSystemPrompt(AppSettings.DEFAULT_SYSTEM_PROMPT) }
    }

    fun setTemperature(value: Double) = updateGeneration { it.copy(temperature = value) }

    fun setTopP(value: Double) = updateGeneration { it.copy(topP = value) }

    fun setTopK(value: Int) = updateGeneration { it.copy(topK = value) }

    fun setRepeatPenalty(value: Double) = updateGeneration { it.copy(repeatPenalty = value) }

    fun setMaxTokens(value: Int) = updateGeneration { it.copy(maxTokens = value) }

    fun setContextLength(value: Int) = updateGeneration { it.copy(contextLength = value) }

    fun setThreadCount(value: Int) = updateRuntime { it.copy(threadCount = value) }

    fun setBatchSize(value: Int) = updateRuntime { it.copy(batchSize = value) }

    fun setLowMemory(enabled: Boolean) = updateRuntime { it.copy(lowMemory = enabled) }

    fun setToolsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setToolsEnabled(enabled) }
    }

    fun setAgentAutoReadOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAgentAutoReadOnly(enabled) }
    }

    fun refreshTermuxStatus() {
        _termuxStatus.value = termuxBridge.status()
    }

    fun clearAllChats() {
        viewModelScope.launch {
            conversationRepository.clearAll()
            refreshStorage()
        }
    }

    private fun updateGeneration(block: (GenerationParams) -> GenerationParams) {
        viewModelScope.launch {
            val current = settingsRepository.current()
            settingsRepository.setGenerationParams(block(current.generation))
        }
    }

    private fun updateRuntime(block: (RuntimeDefaults) -> RuntimeDefaults) {
        viewModelScope.launch {
            val current = settingsRepository.current()
            settingsRepository.setRuntimeDefaults(block(current.runtime))
        }
    }
}
