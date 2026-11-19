package com.localmind.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.inference.estimateTokens
import com.localmind.app.core.tool.CommandPolicy
import com.localmind.app.core.tool.ToolCallParser
import com.localmind.app.core.ui.components.InfoBanner
import com.localmind.app.core.ui.components.TextInputDialog
import com.localmind.app.core.ui.theme.LocalMindExtras
import com.localmind.app.core.util.formatTokenCount
import com.localmind.app.feature.chat.components.ChatComposer
import com.localmind.app.feature.chat.components.ConversationDrawerContent
import com.localmind.app.feature.chat.components.MessageBubble
import com.localmind.app.feature.chat.components.ModelPickerSheet
import com.localmind.app.feature.chat.components.StreamingBubble
import com.localmind.app.feature.chat.components.ToolCardState
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onManageModels: () -> Unit,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversation by viewModel.activeConversation.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val toolRunning by viewModel.toolRunning.collectAsStateWithLifecycle()
    val agentStep by viewModel.agentStep.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val searchQuery by viewModel.search.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()

    val extras = LocalMindExtras.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showModelPicker by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val activeModel = models.firstOrNull { it.id == settings.activeModelId }
    val modelLabel = if (viewModel.isDemoEngine) {
        viewModel.engineDisplayName
    } else {
        activeModel?.displayName ?: viewModel.engineDisplayName
    }
    val statusLabel = when (status) {
        ChatStatus.Idle -> "Ready"
        ChatStatus.LoadingModel -> "Loading model…"
        ChatStatus.Thinking -> "Thinking…"
        ChatStatus.Generating -> if (agentStep > 0) {
            "Step $agentStep/${viewModel.agentMaxSteps}"
        } else {
            "Generating…"
        }
        ChatStatus.RunningTool -> if (agentStep > 0) {
            "Step $agentStep/${viewModel.agentMaxSteps}"
        } else {
            "Running tool…"
        }
        ChatStatus.Error -> "Error"
    }
    val statusColor = when (status) {
        ChatStatus.Idle -> MaterialTheme.colorScheme.secondary
        ChatStatus.LoadingModel -> MaterialTheme.colorScheme.tertiary
        ChatStatus.Thinking, ChatStatus.Generating, ChatStatus.RunningTool -> MaterialTheme.colorScheme.primary
        ChatStatus.Error -> MaterialTheme.colorScheme.error
    }

    val contextLength = activeModel?.contextLength?.takeIf { it > 0 } ?: settings.generation.contextLength
    val contextUsed = remember(messages, streamingText, settings.systemPrompt) {
        val history = messages.sumOf { if (it.content.isBlank()) 0 else estimateTokens(it.content) }
        val streaming = streamingText?.takeIf { it.isNotBlank() }?.let { estimateTokens(it) } ?: 0
        estimateTokens(settings.systemPrompt) + history + streaming
    }
    val contextFraction = (contextUsed.toFloat() / contextLength.toFloat()).coerceIn(0f, 1f)

    val lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    val lastUserId = messages.lastOrNull { it.isUser }?.id

    val lastMessage = messages.lastOrNull()
    val pendingToolCall = remember(lastMessage?.id, lastMessage?.content) {
        if (lastMessage?.role == MessageRole.ASSISTANT) {
            ToolCallParser.split(lastMessage.content).call
        } else {
            null
        }
    }
    val pendingToolMessageId = if (pendingToolCall != null) lastMessage?.id else null

    val pendingAssessment = remember(pendingToolCall?.command) {
        pendingToolCall?.command
            ?.takeIf { it.isNotBlank() }
            ?.let { CommandPolicy.assess(it) }
    }
    val pendingBlocked = pendingAssessment?.blocked == true
    val pendingPolicyNote = pendingAssessment?.let { assessment ->
        when (assessment.risk) {
            CommandPolicy.Risk.AUTO_READ ->
                if (settings.toolsEnabled && settings.agentAutoReadOnly) {
                    "Read-only command. Smart mode runs this without a tap."
                } else {
                    "Read-only command. Auto-run is off, so approve it manually."
                }
            CommandPolicy.Risk.REVIEW ->
                "This command can change files, packages, or network state, so it needs your approval."
            CommandPolicy.Risk.BLOCKED ->
                assessment.reason ?: "This command is blocked by the safety policy."
        }
    }

    LaunchedEffect(activeConversationId) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(streamingText?.length ?: 0) {
        if (streamingText != null && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    val showJumpToLatest by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 312.dp),
            ) {
                ConversationDrawerContent(
                    conversations = conversations,
                    activeConversationId = activeConversationId,
                    searchQuery = searchQuery,
                    onSearchChange = viewModel::setSearchQuery,
                    onNewChat = {
                        viewModel.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onSelect = { id ->
                        viewModel.selectConversation(id)
                        scope.launch { drawerState.close() }
                    },
                    onRename = { conversation, title ->
                        viewModel.renameConversation(conversation.id, title)
                    },
                    onTogglePin = viewModel::togglePinned,
                    onDelete = { viewModel.deleteConversation(it.id) },
                    onClose = { scope.launch { drawerState.close() } },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(bottom = 4.dp),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            ChatHeader(
                title = activeConversation?.title ?: "New chat",
                modelLabel = modelLabel,
                statusLabel = statusLabel,
                statusColor = statusColor,
                isDemoEngine = viewModel.isDemoEngine,
                thinkingEnabled = activeModel?.isThinkingEnabled == true,
                onThinkingToggle = {
                    viewModel.setThinkingEnabled(activeModel?.isThinkingEnabled != true)
                },
                onMenu = { scope.launch { drawerState.open() } },
                onNewChat = { viewModel.newChat() },
                onModelClick = { showModelPicker = true },
            )

            if (messages.isNotEmpty() || streamingText != null) {
                ContextUsageBar(
                    used = contextUsed,
                    total = contextLength,
                    fraction = contextFraction,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty() && streamingText == null) {
                    EmptyChat(
                        isDemoEngine = viewModel.isDemoEngine,
                        onSuggestion = { prompt ->
                            viewModel.sendMessage(prompt)
                            scope.launch { listState.scrollToItem(0) }
                        },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (streamingText != null || isGenerating) {
                            item(key = "streaming") {
                                StreamingBubble(
                                    text = streamingText.orEmpty(),
                                    modelLabel = modelLabel,
                                    statusLabel = statusLabel,
                                )
                            }
                        }
                        items(messages.asReversed(), key = { it.id }) { message ->
                            val isPendingTool = message.id == pendingToolMessageId
                            MessageBubble(
                                message = message,
                                modelLabel = if (message.isUser) null else modelLabel,
                                canRetry = message.id == lastAssistantId && !isGenerating && !toolRunning,
                                canEdit = message.id == lastUserId && !isGenerating && !toolRunning,
                                toolCardState = when {
                                    !isPendingTool -> ToolCardState.Settled
                                    toolRunning -> ToolCardState.Running
                                    isGenerating -> ToolCardState.Settled
                                    else -> ToolCardState.Pending
                                },
                                onRunTool = if (
                                    isPendingTool && !pendingBlocked && !isGenerating && !toolRunning
                                ) {
                                    {
                                        viewModel.runToolCall(message.id)
                                        scope.launch { listState.animateScrollToItem(0) }
                                    }
                                } else {
                                    null
                                },
                                onDeclineTool = if (isPendingTool && !isGenerating && !toolRunning) {
                                    { viewModel.declineToolCall(message.id) }
                                } else {
                                    null
                                },
                                toolPolicyNote = if (isPendingTool) pendingPolicyNote else null,
                                toolBlocked = isPendingTool && pendingBlocked,
                                onRetry = {
                                    viewModel.regenerate()
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                onEdit = { editingMessage = message },
                            )
                        }
                    }
                }

                JumpToLatestButton(
                    visible = showJumpToLatest,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 10.dp),
                )
            }

            error?.let { message ->
                ErrorBanner(
                    message = message,
                    canRetry = messages.any { it.isUser },
                    onRetry = {
                        viewModel.clearError()
                        viewModel.regenerate()
                    },
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            ChatComposer(
                value = draft,
                onValueChange = viewModel::onDraftChange,
                onSend = {
                    viewModel.sendDraft()
                    scope.launch { listState.animateScrollToItem(0) }
                },
                onStop = viewModel::stopGeneration,
                isGenerating = isGenerating || toolRunning,
                modelLabel = modelLabel,
                onModelClick = { showModelPicker = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = models,
            activeModelId = settings.activeModelId,
            isDemoEngine = viewModel.isDemoEngine,
            onSelect = viewModel::setActiveModel,
            onManageModels = {
                showModelPicker = false
                onManageModels()
            },
            onDismiss = { showModelPicker = false },
        )
    }

    editingMessage?.let { message ->
        TextInputDialog(
            title = "Edit message",
            label = "Message",
            initialValue = message.content,
            confirmLabel = "Save & regenerate",
            multiline = true,
            onConfirm = { newText -> viewModel.editUserMessage(message.id, newText) },
            onDismiss = { editingMessage = null },
        )
    }
}

@Composable
private fun JumpToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = "Jump to latest message",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ChatHeader(
    title: String,
    modelLabel: String,
    statusLabel: String,
    statusColor: Color,
    isDemoEngine: Boolean,
    thinkingEnabled: Boolean,
    onThinkingToggle: () -> Unit,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onModelClick: () -> Unit,
) {
    val extras = LocalMindExtras.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "Open chat history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onModelClick)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(statusColor, CircleShape),
                    )
                    Text(
                        text = "$modelLabel · $statusLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isDemoEngine) {
                        Text(
                            text = "DEMO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            if (!isDemoEngine) {
                IconButton(onClick = onThinkingToggle) {
                    Icon(
                        imageVector = Icons.Outlined.Lightbulb,
                        contentDescription = if (thinkingEnabled) {
                            "Disable thinking mode"
                        } else {
                            "Enable thinking mode"
                        },
                        tint = if (thinkingEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            extras.mutedText
                        },
                    )
                }
            }
            IconButton(onClick = onNewChat) {
                Icon(
                    imageVector = Icons.Outlined.AddComment,
                    contentDescription = "New chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = extras.border.copy(alpha = 0.55f))
    }
}

@Composable
private fun ContextUsageBar(used: Int, total: Int, fraction: Float) {
    val extras = LocalMindExtras.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(3.dp),
            color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = "${formatTokenCount(used)} / ${formatTokenCount(total)} ctx",
            style = MaterialTheme.typography.labelSmall,
            color = extras.mutedText,
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (canRetry) {
                TextButton(onClick = onRetry) {
                    Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

private data class ChatSuggestion(
    val icon: ImageVector,
    val title: String,
    val prompt: String,
)

private val CHAT_SUGGESTIONS = listOf(
    ChatSuggestion(
        icon = Icons.Outlined.Code,
        title = "Explain Kotlin concurrency",
        prompt = "Explain Kotlin coroutines and structured concurrency with a short code example.",
    ),
    ChatSuggestion(
        icon = Icons.Outlined.Lightbulb,
        title = "Sketch a Compose screen",
        prompt = "Sketch a Jetpack Compose screen with a search field and a lazy list. Include the state holder.",
    ),
    ChatSuggestion(
        icon = Icons.Outlined.Memory,
        title = "Plan local inference",
        prompt = "Help me plan an offline Android app that runs a local GGUF model. Focus on RAM limits and model loading.",
    ),
)

@Composable
private fun EmptyChat(
    isDemoEngine: Boolean,
    onSuggestion: (String) -> Unit,
) {
    val extras = LocalMindExtras.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Your model, your device",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Ask anything. Chats stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.mutedText,
        )
        Spacer(Modifier.height(22.dp))

        if (isDemoEngine) {
            InfoBanner(
                icon = Icons.Outlined.Info,
                text = "You are chatting with the demo engine. Responses are simulated; no model file is read. " +
                    "Import a GGUF model in Models to prepare for Milestone 2.",
            )
            Spacer(Modifier.height(18.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CHAT_SUGGESTIONS.forEach { suggestion ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSuggestion(suggestion.prompt) },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = suggestion.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = suggestion.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            tint = extras.mutedText,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Prompts and replies are stored locally in Room.",
            style = MaterialTheme.typography.labelSmall,
            color = extras.mutedText,
        )
    }
}
