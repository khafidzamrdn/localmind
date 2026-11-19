package com.localmind.app.feature.chat

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
    Error,
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val engine: InferenceEngine,
    private val controller: LocalInferenceController,
) : ViewModel() {

    private val currentConversationId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val _draft = MutableStateFlow("")
    private val _streamingText = MutableStateFlow<String?>(null)
    private val _isGenerating = MutableStateFlow(false)
    private val _status = MutableStateFlow(ChatStatus.Idle)
    private val _error = MutableStateFlow<String?>(null)

    val draft: StateFlow<String> = _draft.asStateFlow()
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    val status: StateFlow<ChatStatus> = _status.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

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

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun selectConversation(conversationId: String) {
        if (conversationId == currentConversationId.value) return
        cancelGeneration()
        currentConversationId.value = conversationId
        _streamingText.value = null
        _error.value = null
        _status.value = ChatStatus.Idle
    }

    fun newChat() {
        cancelGeneration()
        currentConversationId.value = null
        _draft.value = ""
        _streamingText.value = null
        _error.value = null
        _status.value = ChatStatus.Idle
    }

    fun clearError() {
        _error.value = null
        if (!_isGenerating.value) _status.value = ChatStatus.Idle
    }

    fun sendDraft() {
        val text = _draft.value.trim()
        if (text.isEmpty() || _isGenerating.value) return
        _draft.value = ""
        startUserTurn(text)
    }

    /** Sends [text] immediately; used by the empty-state suggestions. */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        _draft.value = ""
        startUserTurn(trimmed)
    }

    /** Regenerates the latest assistant reply for the active conversation. */
    fun regenerate() {
        val conversationId = currentConversationId.value ?: return
        if (_isGenerating.value) return
        generationJob = viewModelScope.launch {
            val all = conversationRepository.getMessages(conversationId)
            val lastUserIndex = all.indexOfLast { it.role == MessageRole.USER }
            val lastAssistantIndex = all.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (lastUserIndex < 0) return@launch
            if (lastAssistantIndex > lastUserIndex) {
                conversationRepository.deleteMessage(all[lastAssistantIndex].id)
            }
            generate(conversationId)
        }
    }

    /**
     * Edits a user message in place, drops every later message and regenerates.
     * Editing an older message intentionally discards the branch that followed.
     */
    fun editUserMessage(messageId: Long, newContent: String) {
        val trimmed = newContent.trim()
        val conversationId = currentConversationId.value ?: return
        if (trimmed.isEmpty() || _isGenerating.value) return
        generationJob = viewModelScope.launch {
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
            generate(conversationId)
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

    private fun startUserTurn(prompt: String) {
        generationJob = viewModelScope.launch {
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
            generate(conversationId)
        }
    }

    private suspend fun generate(conversationId: String) {
        _streamingText.value = ""
        _error.value = null
        _isGenerating.value = true
        _status.value = ChatStatus.LoadingModel
        completedNormally = false
        streamingBuffer = StringBuilder()

        val config = settingsRepository.current()
        val model = config.activeModelId?.let { modelRepository.getModel(it) }

        if (model == null && !engine.isDemo) {
            _error.value = "No model is selected. Import a GGUF model in Models, then select and load it."
            _status.value = ChatStatus.Error
            _isGenerating.value = false
            _streamingText.value = null
            return
        }

        if (model != null) {
            val loadResult = controller.ensureLoaded(model.id)
            if (loadResult.isFailure) {
                _error.value = loadResult.exceptionOrNull()?.message ?: "The model could not be loaded."
                _status.value = ChatStatus.Error
                _isGenerating.value = false
                _streamingText.value = null
                return
            }
        }

        _status.value = ChatStatus.Thinking
        val history = conversationRepository.getMessages(conversationId)
        val request = InferenceRequest(
            messages = history,
            systemPrompt = config.systemPrompt,
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
            _error.value = error.message ?: "Generation failed unexpectedly."
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
        _isGenerating.value = false
        _streamingText.value = null
        if (_status.value != ChatStatus.Error) {
            _status.value = ChatStatus.Idle
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }
}
