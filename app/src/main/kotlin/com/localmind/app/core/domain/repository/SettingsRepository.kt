package com.localmind.app.core.domain.repository

import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.GenerationParams
import com.localmind.app.core.domain.model.RuntimeDefaults
import com.localmind.app.core.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setSystemPrompt(prompt: String)

    suspend fun setGenerationParams(params: GenerationParams)

    suspend fun setRuntimeDefaults(defaults: RuntimeDefaults)

    suspend fun setActiveModel(modelId: String?)
}
