package com.localmind.app.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.GenerationParams
import com.localmind.app.core.domain.model.RuntimeDefaults
import com.localmind.app.core.domain.model.ThemeMode
import com.localmind.app.core.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "localmind_settings",
)

class SettingsRepositoryImpl(
    private val context: Context,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
        .map { prefs -> prefs.toSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setSystemPrompt(prompt: String) {
        context.settingsDataStore.edit { it[Keys.SYSTEM_PROMPT] = prompt }
    }

    override suspend fun setGenerationParams(params: GenerationParams) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TEMPERATURE] = params.temperature.toFloat()
            prefs[Keys.TOP_P] = params.topP.toFloat()
            prefs[Keys.TOP_K] = params.topK
            prefs[Keys.REPEAT_PENALTY] = params.repeatPenalty.toFloat()
            prefs[Keys.MAX_TOKENS] = params.maxTokens
            prefs[Keys.CONTEXT_LENGTH] = params.contextLength
        }
    }

    override suspend fun setActiveModel(modelId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (modelId == null) {
                prefs.remove(Keys.ACTIVE_MODEL)
            } else {
                prefs[Keys.ACTIVE_MODEL] = modelId
            }
        }
    }

    override suspend fun setRuntimeDefaults(defaults: RuntimeDefaults) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THREAD_COUNT] = defaults.threadCount
            prefs[Keys.BATCH_SIZE] = defaults.batchSize
            prefs[Keys.GPU_ENABLED] = defaults.gpuEnabled
            prefs[Keys.LOW_MEMORY] = defaults.lowMemory
        }
    }

    override suspend fun setToolsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.AGENT_TOOLS_ENABLED] = enabled }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            systemPrompt = this[Keys.SYSTEM_PROMPT] ?: defaults.systemPrompt,
            activeModelId = this[Keys.ACTIVE_MODEL],
            toolsEnabled = this[Keys.AGENT_TOOLS_ENABLED] ?: defaults.toolsEnabled,
            generation = GenerationParams(
                temperature = (this[Keys.TEMPERATURE] ?: defaults.generation.temperature.toFloat()).toDouble(),
                topP = (this[Keys.TOP_P] ?: defaults.generation.topP.toFloat()).toDouble(),
                topK = this[Keys.TOP_K] ?: defaults.generation.topK,
                repeatPenalty = (this[Keys.REPEAT_PENALTY] ?: defaults.generation.repeatPenalty.toFloat()).toDouble(),
                maxTokens = this[Keys.MAX_TOKENS] ?: defaults.generation.maxTokens,
                contextLength = this[Keys.CONTEXT_LENGTH] ?: defaults.generation.contextLength,
            ),
            runtime = RuntimeDefaults(
                threadCount = this[Keys.THREAD_COUNT] ?: defaults.runtime.threadCount,
                batchSize = this[Keys.BATCH_SIZE] ?: defaults.runtime.batchSize,
                gpuEnabled = this[Keys.GPU_ENABLED] ?: defaults.runtime.gpuEnabled,
                lowMemory = this[Keys.LOW_MEMORY] ?: defaults.runtime.lowMemory,
            ),
        )
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val ACTIVE_MODEL = stringPreferencesKey("active_model_id")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val CONTEXT_LENGTH = intPreferencesKey("context_length")
        val THREAD_COUNT = intPreferencesKey("runtime_thread_count")
        val BATCH_SIZE = intPreferencesKey("runtime_batch_size")
        val GPU_ENABLED = booleanPreferencesKey("runtime_gpu_enabled")
        val LOW_MEMORY = booleanPreferencesKey("runtime_low_memory")
        val AGENT_TOOLS_ENABLED = booleanPreferencesKey("agent_tools_enabled")
    }
}
