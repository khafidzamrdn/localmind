package com.localmind.app.core.data.repository

import com.localmind.app.core.data.local.ModelDao
import com.localmind.app.core.data.local.toDomain
import com.localmind.app.core.data.local.toEntity
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ModelRepositoryImpl(
    private val modelDao: ModelDao,
) : ModelRepository {

    override fun observeModels(): Flow<List<LocalModel>> =
        modelDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getModel(modelId: String): LocalModel? =
        modelDao.getById(modelId)?.toDomain()

    override suspend fun findByUri(uri: String): LocalModel? =
        modelDao.findByUri(uri)?.toDomain()

    override suspend fun addModel(model: LocalModel) {
        modelDao.upsert(model.toEntity())
    }

    override suspend fun removeModel(modelId: String) {
        modelDao.delete(modelId)
    }
}
