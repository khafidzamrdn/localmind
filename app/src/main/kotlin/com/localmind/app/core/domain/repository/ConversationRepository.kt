package com.localmind.app.core.domain.repository

import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.Conversation
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    /** Empty [query] returns every conversation, newest update first. */
    fun observeConversations(query: String = ""): Flow<List<Conversation>>

    fun observeConversation(conversationId: String): Flow<Conversation?>

    suspend fun getConversation(conversationId: String): Conversation?

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    suspend fun getMessages(conversationId: String): List<ChatMessage>

    suspend fun createConversation(title: String, modelId: String?): Conversation

    suspend fun renameConversation(conversationId: String, title: String)

    suspend fun setPinned(conversationId: String, pinned: Boolean)

    suspend fun deleteConversation(conversationId: String)

    suspend fun touchConversation(conversationId: String, title: String? = null)

    suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
        modelId: String? = null,
        tokenCount: Int? = null,
    ): ChatMessage

    suspend fun updateMessage(
        messageId: Long,
        content: String,
        status: MessageStatus,
        tokenCount: Int? = null,
    )

    suspend fun deleteMessage(messageId: Long)

    /** Deletes every message newer than [messageId] inside the conversation. */
    suspend fun deleteMessagesAfter(conversationId: String, messageId: Long)

    suspend fun clearAll()
}
