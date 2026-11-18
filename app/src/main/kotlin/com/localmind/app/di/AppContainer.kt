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

    /**
     * The native llama.cpp backend is added in Milestone 2. Until its shared
     * library is packaged and loadable on the device this binding stays on the
     * clearly labeled demo engine.
     */
    val inferenceEngine: InferenceEngine by lazy { FakeInferenceEngine() }

    val inferenceController: LocalInferenceController by lazy {
        LocalInferenceController(
            engine = inferenceEngine,
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
        )
    }

    val databaseFile: java.io.File get() = appContext.getDatabasePath(LocalMindDatabase.DATABASE_NAME)
}
