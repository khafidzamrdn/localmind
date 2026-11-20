package com.localmind.app.feature.chat.components

import com.localmind.app.core.i18n.UiKey
import com.localmind.app.core.i18n.t

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.tool.ToolCall
import com.localmind.app.core.tool.ToolResultCodec
import com.localmind.app.core.tool.ToolResultContent
import com.localmind.app.core.tool.ToolStatus
import com.localmind.app.core.ui.components.StatusPill
import com.localmind.app.core.ui.theme.LocalMindExtras
import kotlinx.coroutines.delay

/** How the card inside an assistant bubble should behave right now. */
enum class ToolCardState {
    /** Newest proposal; waiting for the user to approve or dismiss it. */
    Pending,

    /** Approved; the command is running in Termux. */
    Running,

    /** No longer actionable (already handled, or an older message). */
    Settled,
}

@Composable
fun ToolCallCard(
    call: ToolCall?,
    rawBlock: String?,
    malformed: Boolean,
    state: ToolCardState,
    onRun: (() -> Unit)?,
    onDecline: (() -> Unit)?,
    policyNote: String? = null,
    blocked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.9f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = t(UiKey.chatToolCommand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = when {
                        blocked -> t(UiKey.chatToolBlocked)
                        malformed -> t(UiKey.chatToolInvalid)
                        state == ToolCardState.Running -> t(UiKey.chatToolRunning)
                        state == ToolCardState.Pending -> t(UiKey.chatToolApproval)
                        else -> t(UiKey.chatToolProposed)
                    },
                    color = when {
                        blocked || malformed -> MaterialTheme.colorScheme.error
                        state == ToolCardState.Running -> MaterialTheme.colorScheme.primary
                        state == ToolCardState.Pending -> extras.warning
                        else -> extras.mutedText
                    },
                )
            }

            if (call == null) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = t(UiKey.chatToolInvalidExplain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                rawBlock?.takeIf { it.isNotBlank() }?.let { raw ->
                    Spacer(Modifier.size(6.dp))
                    TerminalBlock(text = raw.take(600), tone = extras.mutedText)
                }
            } else {
                call.purpose?.let { purpose ->
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
                Spacer(Modifier.size(6.dp))
                TerminalBlock(
                    text = call.command.orEmpty(),
                    tone = MaterialTheme.colorScheme.onSurface,
                )

                when (state) {
                    ToolCardState.Pending -> {
                        policyNote?.takeIf { it.isNotBlank() }?.let { note ->
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (blocked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    extras.mutedText
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (!blocked) {
                                Button(
                                    onClick = { onRun?.invoke() },
                                    enabled = onRun != null,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(t(UiKey.chatToolRun))
                                }
                            }
                            TextButton(
                                onClick = { onDecline?.invoke() },
                                enabled = onDecline != null,
                            ) { Text(t(UiKey.actionDismiss)) }
                        }
                        Text(
                            text = if (blocked) {
                                t(UiKey.chatToolBlockedNote)
                            } else {
                                t(UiKey.chatToolApprovalNote)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                        )
                    }

                    ToolCardState.Running -> {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "Running in Termux…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    ToolCardState.Settled -> Unit
                }
            }
        }
    }
}

@Composable
fun ToolResultBubble(
    message: ChatMessage,
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

    val data = remember(message.content) { ToolResultCodec.decode(message.content) }
    val pillColor = when (data?.status) {
        ToolStatus.OK -> MaterialTheme.colorScheme.secondary
        ToolStatus.DECLINED -> extras.mutedText
        ToolStatus.BLOCKED -> MaterialTheme.colorScheme.error
        ToolStatus.TIMEOUT, ToolStatus.CANCELLED, ToolStatus.ERROR, null -> extras.warning
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, extras.border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = t(UiKey.chatToolResult),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        text = when (data?.status) {
                            ToolStatus.OK -> "exit ${data.exitCode ?: 0}"
                            ToolStatus.ERROR -> data.exitCode?.let { "exit $it" } ?: t(UiKey.chatToolFailed)
                            ToolStatus.TIMEOUT -> t(UiKey.chatToolTimeout)
                            ToolStatus.DECLINED -> t(UiKey.chatToolDeclined)
                            ToolStatus.BLOCKED -> t(UiKey.chatToolBlocked)
                            ToolStatus.CANCELLED -> t(UiKey.chatToolCancelled)
                            null -> "result"
                        },
                        color = pillColor,
                    )
                }

                if (data == null) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    return@Column
                }

                Spacer(Modifier.size(6.dp))
                Text(
                    text = data.command,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = extras.mutedText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                val stdout = data.stdout.limitForDisplay()
                if (stdout.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    TerminalBlock(text = stdout, tone = MaterialTheme.colorScheme.onSurface)
                }
                val stderr = data.stderr.limitForDisplay()
                if (stderr.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    TerminalBlock(text = stderr, tone = MaterialTheme.colorScheme.error)
                }
                data.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
                if (data.auto) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = t(UiKey.chatToolAutoApproved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (data.status == ToolStatus.DECLINED) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = t(UiKey.chatToolDeclinedExplain),
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
                if (data.status == ToolStatus.BLOCKED) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = t(UiKey.chatToolBlockedExplain),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                val copyText = listOf(data.stdout, data.stderr)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(copyText.ifBlank { data.note.orEmpty() }))
                            copied = true
                        },
                        enabled = copyText.isNotBlank() || !data.note.isNullOrBlank(),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (copied) t(UiKey.chatToolCopied) else t(UiKey.chatToolCopyOutput))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalBlock(text: String, tone: androidx.compose.ui.graphics.Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = tone,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}

private fun String.limitForDisplay(): String =
    if (length <= MAX_DISPLAY_CHARS) this else take(MAX_DISPLAY_CHARS)

private const val MAX_DISPLAY_CHARS = 8_000
