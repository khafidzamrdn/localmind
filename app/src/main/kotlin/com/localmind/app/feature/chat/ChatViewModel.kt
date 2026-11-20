package com.localmind.app.feature.chat

import com.localmind.app.core.i18n.UiKey
import com.localmind.app.core.i18n.t

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.ChatMessage
import com.localmind.app.core.domain.model.Conversation
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.MessageRole
import com.localmind.app.core.domain.model.MessageStatus
import com.localmind.app.core.inference.InferenceEngine
import com.localmind.app.core.inference.InferenceConfig
import com.localmind.app.core.inference.InferenceEvent
import com.localmind.app.core.inference.InferenceRequest
import com.localmind.app.core.inference.LocalInferenceController
import com.localmind.app.core.inference.ModelRuntimeState
import com.localmind.app.core.domain.repository.ConversationRepository
import com.localmind.app.core.domain.repository.ModelRepository
import com.localmind.app.core.domain.repository.SettingsRepository
import com.localmind.app.core.termux.TermuxBridge
import com.localmind.app.core.tool.CommandPolicy
import com.localmind.app.core.tool.ToolCallParser
import com.localmind.app.core.tool.ToolRegistry
import com.localmind.app.core.tool.ToolResultCodec
import com.localmind.app.core.tool.ToolStatus
import com.localmind.app.core.util.deriveConversationTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Observable agent/engine status shown in the chat header. */
enum class ChatStatus {
    Idle,
    LoadingModel,
    Thinking,
    Generating,
    RunningTool,
    Error,
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val engine: InferenceEngine,
    private val controller: LocalInferenceController,
    private val termuxBridge: TermuxBridge,
) : ViewModel() {

    private val currentConversationId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val _draft = MutableStateFlow("")
    private val _streamingText = MutableStateFlow<String?>(null)
    private val _isGenerating = MutableStateFlow(false)
    private val _toolRunning = MutableStateFlow(false)
    private val _status = MutableStateFlow(ChatStatus.Idle)
    private val _error = MutableStateFlow<String?>(null)
    private val _agentStep = MutableStateFlow(0)

    val draft: StateFlow<String> = _draft.asStateFlow()
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    val toolRunning: StateFlow<Boolean> = _toolRunning.asStateFlow()
    val status: StateFlow<ChatStatus> = _status.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val agentStep: StateFlow<Int> = _agentStep.asStateFlow()
    val agentMaxSteps: Int = MAX_AGENT_STEPS

    val activeConversationId: StateFlow<String?> = currentConversationId.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = searchQuery
        .flatMapLatest { query -> conversationRepository.observeConversations(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConversation: StateFlow<Conversation?> = currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else conversationRepository.observeConversation(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<ChatMessage>> = currentConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else conversationRepository.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val models: StateFlow<List<LocalModel>> = modelRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val search: StateFlow<String> = searchQuery.asStateFlow()

    val engineDisplayName: String = engine.displayName
    val isDemoEngine: Boolean = engine.isDemo
    val runtimeState: StateFlow<ModelRuntimeState> = controller.state

    private var generationJob: Job? = null
    private var streamingBuffer = StringBuilder()
    private var completedNormally = false

    // Agent-loop budget for the current user turn.
    private var agentSteps = 0
    private var consecutiveFailures = 0
    private val executedCommands = mutableSetOf<String>()
    private var agentTurnRunning = false

    private fun resetAgentTurn() {
        agentSteps = 0
        consecutiveFailures = 0
        executedCommands.clear()
        _agentStep.value = 0
    }

    private fun busy(): Boolean = _isGenerating.value || _toolRunning.value || agentTurnRunning

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun selectConversation(conversationId: String) {
        if (conversationId == currentConversationId.value) return
        cancelGeneration()
        resetAgentTurn()
        currentConversationId.value = conversationId
        _streamingText.value = null
        _error.value = null
        _status.value = ChatStatus.Idle
    }

    fun newChat() {
        cancelGeneration()
        resetAgentTurn()
        currentConversationId.value = null
        _draft.value = ""
        _streamingText.value = null
        _error.value = null
        _status.value = ChatStatus.Idle
    }

    fun clearError() {
        _error.value = null
        if (!_isGenerating.value && !_toolRunning.value) _status.value = ChatStatus.Idle
    }

    fun sendDraft() {
        val text = _draft.value.trim()
        if (text.isEmpty() || busy()) return
        _draft.value = ""
        startUserTurn(text)
    }

    /** Sends [text] immediately; used by the empty-state suggestions. */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy()) return
        _draft.value = ""
        startUserTurn(trimmed)
    }

    /** Regenerates the latest assistant reply for the active conversation. */
    fun regenerate() {
        val conversationId = currentConversationId.value ?: return
        if (busy()) return
        resetAgentTurn()
        generationJob = viewModelScope.launch {
            agentTurnRunning = true
            try {
                val all = conversationRepository.getMessages(conversationId)
                val lastUserIndex = all.indexOfLast { it.role == MessageRole.USER }
                val lastAssistantIndex = all.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (lastUserIndex < 0) return@launch
                if (lastAssistantIndex > lastUserIndex) {
                    val assistant = all[lastAssistantIndex]
                    // A tool result may follow the proposal; drop the whole branch
                    // so a regenerated call is not paired with an old result.
                    conversationRepository.deleteMessagesAfter(conversationId, assistant.id)
                    conversationRepository.deleteMessage(assistant.id)
                }
                runAgentTurn(conversationId)
            } finally {
                agentTurnRunning = false
            }
        }
    }

    /**
     * Edits a user message in place, drops every later message and regenerates.
     * Editing an older message intentionally discards the branch that followed.
     */
    fun editUserMessage(messageId: Long, newContent: String) {
        val trimmed = newContent.trim()
        val conversationId = currentConversationId.value ?: return
        if (trimmed.isEmpty() || busy()) return
        resetAgentTurn()
        generationJob = viewModelScope.launch {
            agentTurnRunning = true
            try {
                conversationRepository.updateMessage(messageId, trimmed, MessageStatus.COMPLETE)
                conversationRepository.deleteMessagesAfter(conversationId, messageId)
                val remaining = conversationRepository.getMessages(conversationId)
                if (remaining.firstOrNull()?.id == messageId) {
                    conversationRepository.touchConversation(
                        conversationId,
                        deriveConversationTitle(trimmed),
                    )
                } else {
                    conversationRepository.touchConversation(conversationId)
                }
                runAgentTurn(conversationId)
            } finally {
                agentTurnRunning = false
            }
        }
    }

    fun stopGeneration() {
        cancelGeneration()
    }

    fun renameConversation(conversationId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { conversationRepository.renameConversation(conversationId, title) }
    }

    fun togglePinned(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepository.setPinned(conversation.id, !conversation.pinned)
        }
    }

    fun deleteConversation(conversationId: String) {
        generationJob?.cancel()
        resetAgentTurn()
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
            if (currentConversationId.value == conversationId) {
                currentConversationId.value = null
                _streamingText.value = null
                _status.value = ChatStatus.Idle
            }
        }
    }

    fun setActiveModel(modelId: String?) {
        viewModelScope.launch { settingsRepository.setActiveModel(modelId) }
    }

    /** Toggles reasoning for the selected model from the chat header. */
    fun setThinkingEnabled(enabled: Boolean) {
        val modelId = settings.value.activeModelId ?: return
        viewModelScope.launch {
            val model = modelRepository.getModel(modelId) ?: return@launch
            modelRepository.updateModel(model.copy(isThinkingEnabled = enabled))
        }
    }

    /**
     * Runs the tool call proposed by [messageId] after the user tapped Run.
     * Only the newest message can carry a pending call, and only one tool may
     * run at a time. After the result, read-only steps may continue on their
     * own until the step budget or a command that needs approval is reached.
     */
    fun runToolCall(messageId: Long) {
        val conversationId = currentConversationId.value ?: return
        if (busy()) return
        generationJob = viewModelScope.launch {
            agentTurnRunning = true
            try {
                if (!performToolCall(conversationId, messageId, auto = false)) return@launch
                runAgentTurn(conversationId)
            } finally {
                agentTurnRunning = false
            }
        }
    }

    /** Generates a reply, then auto-runs safe read-only steps until the budget ends. */
    private suspend fun runAgentTurn(conversationId: String) {
        while (true) {
            generate(conversationId)
            if (_status.value == ChatStatus.Error) return
            val next = pendingAutoToolMessageId(conversationId) ?: return
            if (!performToolCall(conversationId, next, auto = true)) return
        }
    }

    /**
     * Finds the newest assistant tool proposal when smart mode is active.
     * The risk decision itself lives in [performToolCall].
     */
    private suspend fun pendingAutoToolMessageId(conversationId: String): Long? {
        val config = settingsRepository.current()
        if (!config.toolsEnabled || !config.agentAutoReadOnly) return null
        if (!termuxBridge.status().ready) return null
        val all = conversationRepository.getMessages(conversationId)
        val last = all.lastOrNull() ?: return null
        if (last.role != MessageRole.ASSISTANT) return null
        val call = ToolCallParser.split(last.content).call ?: return null
        if (call.name != ToolRegistry.TERMUX_RUN) return null
        if (call.command.isNullOrBlank()) return null
        return last.id
    }

    /**
     * Executes (or refuses) one proposal. Returns false when the call cannot be
     * handled in this mode, which leaves the card waiting for the user.
     */
    private suspend fun performToolCall(
        conversationId: String,
        messageId: Long,
        auto: Boolean,
    ): Boolean {
        if (conversationRepository.getConversation(conversationId) == null) return false
        val all = conversationRepository.getMessages(conversationId)
        val message = all.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
            ?: return false
        if (all.lastOrNull()?.id != messageId) return false
        val call = ToolCallParser.split(message.content).call ?: return false
        if (call.name != ToolRegistry.TERMUX_RUN) return false
        val command = call.command?.takeIf { it.isNotBlank() } ?: return false

        val config = settingsRepository.current()
        if (!config.toolsEnabled || !termuxBridge.status().ready) return false

        val assessment = CommandPolicy.assess(command)
        if (assessment.blocked) {
            // A blocked call never executes, but the model is told why so it
            // can choose a safer next step while the automatic loop runs.
            if (!auto) return false
            if (!canAutoContinue()) return false
            agentSteps++
            _agentStep.value = agentSteps.coerceAtMost(MAX_AGENT_STEPS)
            val blocked = ToolResultCodec.encode(ToolResultCodec.blocked(call, assessment.reason))
            addToolResult(conversationId, blocked, MessageStatus.COMPLETE, config.activeModelId)
            return true
        }

        if (auto) {
            if (!config.agentAutoReadOnly) return false
            if (!canAutoContinue()) return false
            if (!assessment.canAutoRun) return false
            if (normalizeCommand(command) in executedCommands) return false
        }

        agentSteps++
        _agentStep.value = agentSteps.coerceAtMost(MAX_AGENT_STEPS)
        executedCommands += normalizeCommand(command)

        val modelId = config.activeModelId
        _toolRunning.value = true
        _error.value = null
        _status.value = ChatStatus.RunningTool

        try {
            val content = try {
                val result = termuxBridge.run(
                    command = command,
                    timeoutMs = TERMUX_TOOL_TIMEOUT_MS,
                    label = call.purpose ?: "LocalMind tool",
                )
                ToolResultCodec.encode(ToolResultCodec.fromRun(call, result, auto = auto))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (conversationRepository.getConversation(conversationId) != null) {
                        val partial = ToolResultCodec.encode(ToolResultCodec.cancelled(call))
                        conversationRepository.addMessage(
                            conversationId = conversationId,
                            role = MessageRole.TOOL,
                            content = partial,
                            status = MessageStatus.STOPPED,
                            modelId = modelId,
                            tokenCount = com.localmind.app.core.inference.estimateTokens(partial),
                        )
                        conversationRepository.touchConversation(conversationId)
                    }
                }
                throw cancelled
            }

            // The user may have deleted the chat while the command ran.
            if (conversationRepository.getConversation(conversationId) == null) return false

            val decoded = ToolResultCodec.decode(content)
            consecutiveFailures = if (decoded?.status == ToolStatus.OK) 0 else consecutiveFailures + 1
            addToolResult(conversationId, content, MessageStatus.COMPLETE, modelId)
            return true
        } finally {
            _toolRunning.value = false
            if (_status.value != ChatStatus.Error) _status.value = ChatStatus.Idle
        }
    }

    private fun canAutoContinue(): Boolean =
        agentSteps < MAX_AGENT_STEPS && consecutiveFailures < MAX_CONSECUTIVE_FAILURES

    private suspend fun addToolResult(
        conversationId: String,
        content: String,
        status: MessageStatus,
        modelId: String?,
    ) {
        conversationRepository.addMessage(
            conversationId = conversationId,
            role = MessageRole.TOOL,
            content = content,
            status = status,
            modelId = modelId,
            tokenCount = com.localmind.app.core.inference.estimateTokens(content),
        )
        conversationRepository.touchConversation(conversationId)
    }

    private fun normalizeCommand(command: String): String =
        command.trim().replace(Regex("\\s+"), " ")

    /** Records that the user declined the proposal so the card stops asking. */
    fun declineToolCall(messageId: Long) {
        val conversationId = currentConversationId.value ?: return
        if (busy()) return
        viewModelScope.launch {
            val all = conversationRepository.getMessages(conversationId)
            val message = all.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
                ?: return@launch
            if (all.lastOrNull()?.id != messageId) return@launch
            val call = ToolCallParser.split(message.content).call ?: return@launch
            val content = ToolResultCodec.encode(ToolResultCodec.declined(call))
            conversationRepository.addMessage(
                conversationId = conversationId,
                role = MessageRole.TOOL,
                content = content,
                status = MessageStatus.COMPLETE,
                modelId = settingsRepository.current().activeModelId,
                tokenCount = com.localmind.app.core.inference.estimateTokens(content),
            )
            conversationRepository.touchConversation(conversationId)
        }
    }

    private fun startUserTurn(prompt: String) {
        resetAgentTurn()
        generationJob = viewModelScope.launch {
            agentTurnRunning = true
            try {
                val existingId = currentConversationId.value
                val conversationId = existingId ?: run {
                    val created = conversationRepository.createConversation(
                        title = deriveConversationTitle(prompt),
                        modelId = settingsRepository.current().activeModelId,
                    )
                    currentConversationId.value = created.id
                    created.id
                }

                conversationRepository.addMessage(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = prompt,
                )
                conversationRepository.touchConversation(conversationId)
                runAgentTurn(conversationId)
            } finally {
                agentTurnRunning = false
            }
        }
    }

    private suspend fun generate(conversationId: String) {
        if (conversationRepository.getConversation(conversationId) == null) return
        _streamingText.value = ""
        _error.value = null
        _isGenerating.value = true
        _status.value = ChatStatus.LoadingModel
        completedNormally = false
        streamingBuffer = StringBuilder()

        val config = settingsRepository.current()
        val model = config.activeModelId?.let { modelRepository.getModel(it) }

        if (model == null && !engine.isDemo) {
            _error.value = t(UiKey.chatErrorNoModel)
            _status.value = ChatStatus.Error
            _isGenerating.value = false
            _streamingText.value = null
            return
        }

        if (model != null) {
            val loadResult = controller.ensureLoaded(model.id)
            if (loadResult.isFailure) {
                _error.value = loadResult.exceptionOrNull()?.message ?: t(UiKey.chatErrorLoadFailed)
                _status.value = ChatStatus.Error
                _isGenerating.value = false
                _streamingText.value = null
                return
            }
        }

        _status.value = ChatStatus.Thinking
        val history = conversationRepository.getMessages(conversationId)
        val toolsActive = config.toolsEnabled && termuxBridge.status().ready
        val systemPrompt = if (toolsActive) {
            config.systemPrompt.trimEnd() + "\n\n" + ToolRegistry.SYSTEM_PROMPT.trim()
        } else {
            config.systemPrompt
        }
        val request = InferenceRequest(
            messages = history,
            systemPrompt = systemPrompt,
            params = config.generation,
            model = model,
            config = model?.let { InferenceConfig.from(it) } ?: InferenceConfig(),
            thinkingEnabled = model?.isThinkingEnabled == true,
        )

        var lastFlush = 0L
        var usageTokens: Int? = null
        var failedMessage: String? = null

        try {
            engine.generate(request).collect { event ->
                when (event) {
                    is InferenceEvent.Token -> {
                        _status.value = ChatStatus.Generating
                        streamingBuffer.append(event.text)
                        val now = System.currentTimeMillis()
                        // Throttle UI updates so Markdown re-parsing does not run per token.
                        if (now - lastFlush >= 45 || streamingBuffer.length <= 8) {
                            _streamingText.value = streamingBuffer.toString()
                            lastFlush = now
                        }
                    }

                    is InferenceEvent.Completed -> {
                        usageTokens = event.usage.generatedTokens
                    }

                    is InferenceEvent.Failed -> {
                        failedMessage = event.message
                        _status.value = ChatStatus.Error
                    }
                }
            }

            val content = streamingBuffer.toString().trimEnd()
            if (content.isNotEmpty()) {
                conversationRepository.addMessage(
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    status = if (failedMessage != null) MessageStatus.ERROR else MessageStatus.COMPLETE,
                    modelId = model?.id,
                    tokenCount = usageTokens,
                )
            }
            completedNormally = true
            if (failedMessage != null) {
                _error.value = failedMessage
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _status.value = ChatStatus.Error
            _error.value = error.message ?: t(UiKey.chatErrorGeneration)
        } finally {
            if (!completedNormally) {
                withContext(NonCancellable) {
                    val conversationStillExists = conversationRepository.getConversation(conversationId) != null
                    val partial = streamingBuffer.toString().trimEnd()
                    if (conversationStillExists && partial.isNotEmpty()) {
                        conversationRepository.addMessage(
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = partial,
                            status = MessageStatus.STOPPED,
                            modelId = model?.id,
                            tokenCount = com.localmind.app.core.inference.estimateTokens(partial),
                        )
                    }
                    if (conversationStillExists) {
                        conversationRepository.touchConversation(conversationId)
                    }
                }
            }
            _streamingText.value = null
            _isGenerating.value = false
            if (_status.value != ChatStatus.Error) {
                _status.value = ChatStatus.Idle
            }
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        agentTurnRunning = false
        _isGenerating.value = false
        _toolRunning.value = false
        _streamingText.value = null
        if (_status.value != ChatStatus.Error) {
            _status.value = ChatStatus.Idle
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val TERMUX_TOOL_TIMEOUT_MS = 30_000L
        const val MAX_AGENT_STEPS = 6
        const val MAX_CONSECUTIVE_FAILURES = 2
    }
}
