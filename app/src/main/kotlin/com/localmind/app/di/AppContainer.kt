package com.localmind.app.di

import android.content.Context
import com.localmind.app.core.data.local.LocalMindDatabase
import com.localmind.app.core.data.repository.ConversationRepositoryImpl
import com.localmind.app.core.data.repository.ModelRepositoryImpl
import com.localmind.app.core.data.settings.SettingsRepositoryImpl
import com.localmind.app.core.domain.repository.ConversationRepository
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import com.localmind.app.core.inference.FakeInferenceEngine
import com.localmind.app.core.inference.InferenceEngine

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

    /**
     * Milestone 1 uses a simulated backend. Replacing this single binding with
     * a llama.cpp engine is the intended Milestone 2 integration point.
     */
    val inferenceEngine: InferenceEngine by lazy { FakeInferenceEngine() }

    val databaseFile: java.io.File get() = appContext.getDatabasePath(LocalMindDatabase.DATABASE_NAME)
}
