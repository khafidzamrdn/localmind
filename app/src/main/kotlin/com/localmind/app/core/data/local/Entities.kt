package com.localmind.app.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val modelId: String? = null,
    val projectId: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String,
    val modelId: String? = null,
    val tokenCount: Int? = null,
)

@Entity(
    tableName = "models",
    indices = [Index(value = ["uri"], unique = true)],
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val uri: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val format: String,
    val quantization: String?,
    val loadState: String,
    val backendId: String? = null,
    @ColumnInfo(defaultValue = "'UNVERIFIED'") val validation: String = "UNVERIFIED",
    val validationMessage: String? = null,
    val architecture: String? = null,
    val parameterCount: Long? = null,
    val ggufQuantization: String? = null,
    val trainedContextLength: Int? = null,
    val blockCount: Int? = null,
    val embeddingLength: Int? = null,
    val headCount: Int? = null,
    val headCountKv: Int? = null,
    val chatTemplate: String? = null,
    @ColumnInfo(defaultValue = "4096") val contextLength: Int = 4096,
    @ColumnInfo(defaultValue = "0") val threadCount: Int = 0,
    @ColumnInfo(defaultValue = "512") val batchSize: Int = 512,
    @ColumnInfo(defaultValue = "0") val gpuLayers: Int = 0,
)
