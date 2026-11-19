package com.localmind.app.feature.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.MessageStatus
import com.localmind.app.core.ui.components.MarkdownText
import com.localmind.app.core.ui.components.MetaChip
import com.localmind.app.core.ui.theme.LocalMindExtras
import com.localmind.app.core.util.ThinkingParser
import kotlinx.coroutines.delay

@Composable
fun MessageBubble(
    message: ChatMessage,
    modelLabel: String?,
    canRetry: Boolean,
    canEdit: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.isUser) {
        UserBubble(message = message, canEdit = canEdit, onEdit = onEdit, modifier = modifier)
    } else {
        AssistantBubble(
            message = message,
            modelLabel = modelLabel,
            canRetry = canRetry,
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    canEdit: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = extras.userBubble,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (canEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmallAction(
                    icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy message",
                    onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        copied = true
                    },
                )
                SmallAction(
                    icon = Icons.Outlined.Edit,
                    contentDescription = "Edit message",
                    onClick = onEdit,
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    modelLabel: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AssistantAvatar()
            Text(
                text = modelLabel ?: "LocalMind",
                style = MaterialTheme.typography.labelMedium,
                color = extras.mutedText,
            )
            if (message.status == MessageStatus.STOPPED) {
                MetaChip("stopped")
            } else if (message.status == MessageStatus.ERROR) {
                MetaChip("incomplete", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.size(6.dp))
        val split = remember(message.content) { ThinkingParser.split(message.content) }
        val visibleAnswer = split.answer
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, extras.border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (split.hasReasoning) {
                    ThinkingSection(
                        reasoning = split.reasoning,
                        streaming = false,
                        hasAnswer = visibleAnswer.isNotBlank(),
                        stateKey = message.id,
                    )
                    if (visibleAnswer.isNotBlank()) Spacer(Modifier.size(10.dp))
                }
                if (visibleAnswer.isNotBlank()) {
                    MarkdownText(text = visibleAnswer)
                } else if (!split.hasTags) {
                    MarkdownText(text = message.content)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallAction(
                icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = "Copy response",
                onClick = {
                    clipboard.setText(AnnotatedString(visibleAnswer.ifBlank { message.content }))
                    copied = true
                },
            )
            if (canRetry) {
                SmallAction(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Regenerate response",
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
fun StreamingBubble(
    text: String,
    modelLabel: String?,
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AssistantAvatar()
            Text(
                text = modelLabel ?: "LocalMind",
                style = MaterialTheme.typography.labelMedium,
                color = extras.mutedText,
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(6.dp))
        val split = remember(text) { ThinkingParser.split(text) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, extras.border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (text.isBlank()) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + index * 0.2f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (split.hasReasoning || split.isThinking) {
                        ThinkingSection(
                            reasoning = split.reasoning,
                            streaming = true,
                            hasAnswer = split.answer.isNotBlank(),
                            stateKey = "streaming",
                        )
                        if (split.answer.isNotBlank()) Spacer(Modifier.size(10.dp))
                    }
                    if (split.answer.isNotBlank()) {
                        MarkdownText(text = split.answer)
                    } else if (!split.hasTags && text.isNotBlank()) {
                        MarkdownText(text = text)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LocalMindExtras.current.mutedText,
            modifier = Modifier.size(15.dp),
        )
    }
}
