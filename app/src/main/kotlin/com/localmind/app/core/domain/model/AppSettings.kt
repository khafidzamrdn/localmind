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

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val generation: GenerationParams = GenerationParams(),
    val activeModelId: String? = null,
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
