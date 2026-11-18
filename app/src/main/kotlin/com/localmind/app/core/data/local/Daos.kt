package com.localmind.app.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT * FROM conversations
        WHERE :query = '' OR title LIKE '%' || :query || '%'
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    fun observeAll(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getForConversation(conversationId: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query(
        """
        UPDATE messages
        SET content = :content, status = :status, tokenCount = :tokenCount
        WHERE id = :id
        """,
    )
    suspend fun updateContent(id: Long, content: String, status: String, tokenCount: Int?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :messageId")
    suspend fun deleteAfter(conversationId: String, messageId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("UPDATE models SET loadState = :state, backendId = :backendId WHERE id = :id")
    suspend fun updateLoadState(id: String, state: String, backendId: String?)

    @Query(
        """
        UPDATE models
        SET contextLength = :contextLength, threadCount = :threadCount,
            batchSize = :batchSize, gpuLayers = :gpuLayers
        WHERE id = :id
        """,
    )
    suspend fun updateConfig(
        id: String,
        contextLength: Int,
        threadCount: Int,
        batchSize: Int,
        gpuLayers: Int,
    )

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}
