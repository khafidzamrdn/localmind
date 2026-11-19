package com.localmind.app.core.domain.model

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
}

/**
 * Sampling configuration handed to an inference backend. Defaults are
 * deliberately conservative for small local models.
 */
data class GenerationParams(
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val repeatPenalty: Double = 1.1,
    val maxTokens: Int = 512,
    val contextLength: Int = 4096,
)

/**
 * Device-wide defaults used when a model has no explicit runtime override.
 * `threadCount = 0` means the app picks a conservative default.
 *
 * [lowMemory] applies a conservative profile for small devices: the context is
 * capped at 2k tokens, the batch at 256 and threads at 4, which keeps the KV
 * cache and compute buffers small at the cost of shorter conversations.
 */
data class RuntimeDefaults(
    val threadCount: Int = 0,
    val batchSize: Int = LocalModel.DEFAULT_BATCH_SIZE,
    val gpuEnabled: Boolean = false,
    val lowMemory: Boolean = false,
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val generation: GenerationParams = GenerationParams(),
    val runtime: RuntimeDefaults = RuntimeDefaults(),
    val activeModelId: String? = null,
    /**
     * When true the model is told about the Termux tool and may propose
     * commands. Proposals still require an explicit approval in the chat; this
     * switch never allows autonomous execution.
     */
    val toolsEnabled: Boolean = false,
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are LocalMind, an offline AI assistant running on the user's Android device.
            Answer clearly and concisely. Use Markdown for structure and fenced code blocks
            with a language tag for code. Never claim to have executed a tool, read a file,
            or accessed the network unless a tool result was provided in this conversation.
        """.trimIndent()
    }
}
