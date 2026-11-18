package com.localmind.app.core.domain.repository

import com.localmind.app.core.domain.model.LocalModel
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun observeModels(): Flow<List<LocalModel>>

    suspend fun getModel(modelId: String): LocalModel?

    suspend fun findByUri(uri: String): LocalModel?

    suspend fun addModel(model: LocalModel)

    /** Removes the registry entry only; the user's file is never deleted. */
    suspend fun removeModel(modelId: String)
}
