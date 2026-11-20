package com.localmind.app.feature.chat.components

import com.localmind.app.core.i18n.UiKey
import com.localmind.app.core.i18n.t

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.app.core.domain.model.Conversation
import com.localmind.app.core.ui.components.ConfirmDialog
import com.localmind.app.core.ui.components.TextInputDialog
import com.localmind.app.core.ui.theme.LocalMindExtras
import com.localmind.app.core.util.ConversationGroup
import com.localmind.app.core.util.conversationGroup
import com.localmind.app.core.util.formatTimestamp

@Composable
fun ConversationDrawerContent(
    conversations: List<Conversation>,
    activeConversationId: String?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (Conversation, String) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LocalMind",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = t(UiKey.chatAppTagline),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close history",
                    tint = extras.mutedText,
                )
            }
        }

        FilledTonalButton(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(t(UiKey.actionNewChat))
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search conversations", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = extras.mutedText,
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.height(10.dp))

        val groups = remember(conversations) {
            conversations.groupBy { conversationGroup(it.updatedAt, pinned = it.pinned) }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            ConversationGroup.entries.forEach { group ->
                val groupItems = groups[group].orEmpty()
                if (groupItems.isNotEmpty()) {
                    item(key = "header_${group.name}") {
                        Text(
                            text = t(group.labelKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                            modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(groupItems, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            active = conversation.id == activeConversationId,
                            onSelect = { onSelect(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onTogglePin = { onTogglePin(conversation) },
                            onDelete = { deleteTarget = conversation },
                        )
                    }
                }
            }
            item(key = "footer") {
                Text(
                    text = if (conversations.isEmpty()) {
                        "No conversations yet."
                    } else {
                        "${conversations.size} conversation${if (conversations.size == 1) "" else "s"} stored on device"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 6.dp),
                )
            }
        }
    }

    renameTarget?.let { conversation ->
        TextInputDialog(
            title = "Rename conversation",
            label = t(UiKey.chatTitleLabel),
            initialValue = conversation.title,
            confirmLabel = t(UiKey.actionSave),
            onConfirm = { onRename(conversation, it) },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { conversation ->
        ConfirmDialog(
            title = "Delete conversation?",
            message = "\"${conversation.title}\" and its messages will be removed from this device. The model files are not affected.",
            confirmLabel = t(UiKey.actionDelete),
            destructive = true,
            onConfirm = { onDelete(conversation) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    active: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val extras = LocalMindExtras.current
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onSelect)
                .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = t(UiKey.chatPinned),
                            tint = extras.mutedText,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                    Text(
                        text = formatTimestamp(conversation.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Conversation actions",
                        tint = extras.mutedText,
                        modifier = Modifier.size(17.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(t(UiKey.actionRename)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation.pinned) t(UiKey.chatUnpin) else t(UiKey.chatPin)) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(t(UiKey.actionDelete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
