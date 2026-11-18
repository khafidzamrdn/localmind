package com.localmind.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.GenerationParams
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ThemeMode
import com.localmind.app.core.domain.repository.ConversationRepository
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
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
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val models: StateFlow<List<LocalModel>> = modelRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    init {
        refreshStorage()
    }

    fun refreshStorage() {
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
}
