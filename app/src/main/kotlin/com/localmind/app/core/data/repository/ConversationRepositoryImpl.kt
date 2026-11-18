package com.localmind.app.core.data.repository

import com.localmind.app.core.data.local.ConversationDao
import com.localmind.app.core.data.local.MessageDao
import com.localmind.app.core.data.local.MessageEntity
import com.localmind.app.core.data.local.toDomain
import com.localmind.app.core.data.local.toEntity
import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.Conversation
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.domain.model.MessageStatus
import com.localmind.app.core.domain.repository.ConversationRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepositoryImpl(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationRepository {

    override fun observeConversations(query: String): Flow<List<Conversation>> =
        conversationDao.observeAll(query.trim()).map { rows -> rows.map { it.toDomain() } }

    override fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversationDao.observeById(conversationId).map { it?.toDomain() }

    override suspend fun getConversation(conversationId: String): Conversation? =
        conversationDao.getById(conversationId)?.toDomain()

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messageDao.getForConversation(conversationId).map { it.toDomain() }

    override suspend fun createConversation(title: String, modelId: String?): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { UNTITLED },
            createdAt = now,
            updatedAt = now,
            modelId = modelId,
        )
        conversationDao.upsert(conversation.toEntity())
        return conversation
    }

    override suspend fun renameConversation(conversationId: String, title: String) {
        conversationDao.rename(
            id = conversationId,
            title = title.trim().ifBlank { UNTITLED },
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun setPinned(conversationId: String, pinned: Boolean) {
        conversationDao.setPinned(conversationId, pinned)
    }

    override suspend fun deleteConversation(conversationId: String) {
        conversationDao.delete(conversationId)
    }

    override suspend fun touchConversation(conversationId: String, title: String?) {
        val now = System.currentTimeMillis()
        if (title != null) {
            conversationDao.rename(conversationId, title.trim().ifBlank { UNTITLED }, now)
        } else {
            conversationDao.touch(conversationId, now)
        }
    }

    override suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
        modelId: String?,
        tokenCount: Int?,
    ): ChatMessage {
        val message = MessageEntity(
            conversationId = conversationId,
            role = role.name,
            content = content,
            createdAt = System.currentTimeMillis(),
            status = status.name,
            modelId = modelId,
            tokenCount = tokenCount,
        )
        val id = messageDao.insert(message)
        return message.copy(id = id).toDomain()
    }

    override suspend fun updateMessage(
        messageId: Long,
        content: String,
        status: MessageStatus,
        tokenCount: Int?,
    ) {
        messageDao.updateContent(messageId, content, status.name, tokenCount)
    }

    override suspend fun deleteMessage(messageId: Long) {
        messageDao.deleteById(messageId)
    }

    override suspend fun deleteMessagesAfter(conversationId: String, messageId: Long) {
        messageDao.deleteAfter(conversationId, messageId)
    }

    override suspend fun clearAll() {
        conversationDao.deleteAll()
    }

    private companion object {
        const val UNTITLED = "Untitled chat"
    }
}
