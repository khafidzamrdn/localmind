package com.localmind.app.core.domain.model

/** Who authored a message in a conversation. */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}

/**
 * Lifecycle of a stored message. Streaming responses are only persisted once
 * they finish (or are stopped), so [STREAMING] is mostly a UI hint for rows
 * restored from a previous session that were interrupted.
 */
enum class MessageStatus {
    COMPLETE,
    STREAMING,
    STOPPED,
    ERROR,
}

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val modelId: String? = null,
    val projectId: String? = null,
)

data class ChatMessage(
    val id: Long,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val modelId: String? = null,
    val tokenCount: Int? = null,
) {
    val isUser: Boolean get() = role == MessageRole.USER
}
