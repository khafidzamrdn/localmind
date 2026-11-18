package com.localmind.app.core.data.local

import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.Conversation
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.domain.model.MessageStatus
import com.localmind.app.core.domain.model.ModelFormat
import com.localmind.app.core.domain.model.ModelLoadState
import com.localmind.app.core.domain.model.ModelValidation

fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    modelId = modelId,
    projectId = projectId,
)

fun Conversation.toEntity() = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
    modelId = modelId,
    projectId = projectId,
)

fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT),
    content = content,
    createdAt = createdAt,
    status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
    modelId = modelId,
    tokenCount = tokenCount,
)

fun ChatMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    createdAt = createdAt,
    status = status.name,
    modelId = modelId,
    tokenCount = tokenCount,
)

fun ModelEntity.toDomain() = LocalModel(
    id = id,
    displayName = displayName,
    uri = uri,
    sizeBytes = sizeBytes,
    importedAt = importedAt,
    format = runCatching { ModelFormat.valueOf(format) }.getOrDefault(ModelFormat.UNKNOWN),
    quantization = quantization,
    loadState = runCatching { ModelLoadState.valueOf(loadState) }.getOrDefault(ModelLoadState.NOT_LOADED),
    backendId = backendId,
    validation = runCatching { ModelValidation.valueOf(validation) }.getOrDefault(ModelValidation.UNVERIFIED),
    validationMessage = validationMessage,
    architecture = architecture,
    parameterCount = parameterCount,
    ggufQuantization = ggufQuantization,
    trainedContextLength = trainedContextLength,
    blockCount = blockCount,
    embeddingLength = embeddingLength,
    headCount = headCount,
    headCountKv = headCountKv,
    chatTemplate = chatTemplate,
    contextLength = contextLength,
    threadCount = threadCount,
    batchSize = batchSize,
    gpuLayers = gpuLayers,
)

fun LocalModel.toEntity() = ModelEntity(
    id = id,
    displayName = displayName,
    uri = uri,
    sizeBytes = sizeBytes,
    importedAt = importedAt,
    format = format.name,
    quantization = quantization,
    loadState = loadState.name,
    backendId = backendId,
    validation = validation.name,
    validationMessage = validationMessage,
    architecture = architecture,
    parameterCount = parameterCount,
    ggufQuantization = ggufQuantization,
    trainedContextLength = trainedContextLength,
    blockCount = blockCount,
    embeddingLength = embeddingLength,
    headCount = headCount,
    headCountKv = headCountKv,
    chatTemplate = chatTemplate,
    contextLength = contextLength,
    threadCount = threadCount,
    batchSize = batchSize,
    gpuLayers = gpuLayers,
)
