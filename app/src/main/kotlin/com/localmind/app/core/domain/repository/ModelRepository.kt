package com.localmind.app.core.domain.repository

import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelLoadState
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun observeModels(): Flow<List<LocalModel>>

    suspend fun getModel(modelId: String): LocalModel?

    suspend fun findByUri(uri: String): LocalModel?

    suspend fun addModel(model: LocalModel)

    /** Replaces an existing row, used after validation or metadata refresh. */
    suspend fun updateModel(model: LocalModel)

    suspend fun updateLoadState(modelId: String, state: ModelLoadState, backendId: String? = null)

    suspend fun updateConfig(
        modelId: String,
        contextLength: Int,
        threadCount: Int,
        batchSize: Int,
        gpuLayers: Int,
    )

    /** Removes the registry entry only; the user's file is never deleted. */
    suspend fun removeModel(modelId: String)
}
