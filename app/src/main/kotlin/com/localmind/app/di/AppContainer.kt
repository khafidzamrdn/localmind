package com.localmind.app.di

import android.content.Context
import com.localmind.app.core.data.local.LocalMindDatabase
import com.localmind.app.core.data.repository.ConversationRepositoryImpl
import com.localmind.app.core.data.repository.ModelRepositoryImpl
import com.localmind.app.core.data.settings.SettingsRepositoryImpl
import com.localmind.app.core.domain.repository.ConversationRepository
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import com.localmind.app.core.gguf.GgufModelValidator
import com.localmind.app.core.inference.FakeInferenceEngine
import com.localmind.app.core.inference.InferenceEngine
import com.localmind.app.core.inference.LocalInferenceController
import com.localmind.app.core.inference.ResourceEstimator
import com.localmind.app.core.inference.llama.LlamaCppEngine
import com.localmind.app.core.termux.TermuxBridge

/**
 * Manual dependency container. Kept deliberately small and explicit so the
 * project can migrate to Hilt (or another DI framework) without touching
 * feature code that only knows the interfaces.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val database: LocalMindDatabase by lazy { LocalMindDatabase.build(appContext) }

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepositoryImpl(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
        )
    }

    val modelRepository: ModelRepository by lazy {
        ModelRepositoryImpl(database.modelDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(appContext)
    }

    val ggufValidator: GgufModelValidator by lazy {
        GgufModelValidator(appContext.contentResolver)
    }

    val resourceEstimator: ResourceEstimator by lazy { ResourceEstimator(appContext) }

    val termuxBridge: TermuxBridge by lazy { TermuxBridge(appContext) }

    /**
     * llama.cpp is used when its shared library is packaged for the device
     * ABI and can be loaded. On unsupported builds (for example 32-bit
     * devices or an emulator without the arm64 library) the app stays usable
     * with the clearly labelled demo engine instead of crashing.
     */
    val inferenceEngine: InferenceEngine by lazy {
        LlamaCppEngine.createIfAvailable(appContext.contentResolver, appContext.cacheDir)
            ?: FakeInferenceEngine()
    }

    val inferenceController: LocalInferenceController by lazy {
        LocalInferenceController(
            engine = inferenceEngine,
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
        )
    }

    val databaseFile: java.io.File get() = appContext.getDatabasePath(LocalMindDatabase.DATABASE_NAME)
}
