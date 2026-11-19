package com.localmind.app.core.util

import java.util.Locale

/**
 * Split of one assistant message into the reasoning block and the answer that
 * should be shown as the normal chat response.
 */
data class ThinkingSplit(
    val reasoning: String,
    val answer: String,
    /** True when [answer] ends inside a reasoning block (still streaming). */
    val isThinking: Boolean,
    /** True when at least one reasoning marker was found. */
    val hasTags: Boolean,
) {
    val hasReasoning: Boolean get() = reasoning.isNotBlank()
}

/**
 * Extracts ` thinking`/` response` style reasoning blocks from model output.
 *
 * The parser is tolerant of the variants produced by common reasoning models
 * and of partial output while a response is still streaming. A closing marker
 * without an opening marker is treated as the end of a prefilled block, which
 * is what happens when a chat template already wrote the opening token.
 */
object ThinkingParser {

    private data class Tag(val marker: String, val opens: Boolean)
    private data class Match(val index: Int, val tag: Tag)

    private val TAGS = listOf(
        Tag(" thinking", true),
        Tag(" response", false),
        Tag("<thinking>", true),
        Tag("</thinking>", false),
        Tag("<|think|>", true),
        Tag("<|/think|>", false),
        Tag("<|begin_of_thought|>", true),
        Tag("<|end_of_thought|>", false),
        Tag("<reasoning>", true),
        Tag("</reasoning>", false),
        Tag("[think]", true),
        Tag("[/think]", false),
        Tag("<think", true),
        Tag("</think", false),
    ).sortedByDescending { it.marker.length }

    fun split(raw: String): ThinkingSplit {
        if (raw.isBlank()) return ThinkingSplit("", raw, isThinking = false, hasTags = false)

        val lower = raw.lowercase(Locale.ROOT)
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var cursor = 0
        var inThinking = false
        var sawTag = false

        // Templates that prefill the opening marker make the model emit only
        // the closing marker. Everything before it is reasoning.
        val firstOpen = find(lower, 0, opens = true)
        val firstClose = find(lower, 0, opens = false)
        if (firstClose != null && (firstOpen == null || firstClose.index < firstOpen.index)) {
            reasoning.append(raw, 0, firstClose.index)
            cursor = firstClose.index + firstClose.tag.marker.length
            sawTag = true
        }

        while (cursor < raw.length) {
            if (inThinking) {
                val close = find(lower, cursor, opens = false)
                if (close == null) {
                    reasoning.append(raw, cursor, raw.length)
                    cursor = raw.length
                } else {
                    reasoning.append(raw, cursor, close.index)
                    cursor = close.index + close.tag.marker.length
                    inThinking = false
                }
            } else {
                val open = find(lower, cursor, opens = true)
                val close = find(lower, cursor, opens = false)
                when {
                    open != null && (close == null || open.index <= close.index) -> {
                        answer.append(raw, cursor, open.index)
                        if (reasoning.isNotEmpty()) reasoning.append("\n\n")
                        cursor = open.index + open.tag.marker.length
                        inThinking = true
                        sawTag = true
                    }

                    close != null -> {
                        // Stray closing marker: drop it and keep the text.
                        answer.append(raw, cursor, close.index)
                        cursor = close.index + close.tag.marker.length
                        sawTag = true
                    }

                    else -> {
                        answer.append(raw, cursor, raw.length)
                        cursor = raw.length
                    }
                }
            }
        }

        return ThinkingSplit(
            reasoning = reasoning.toString().trim(),
            answer = answer.toString().trim(),
            isThinking = inThinking,
            hasTags = sawTag,
        )
    }

    /** Visible answer with every reasoning block removed. */
    fun stripReasoning(raw: String): String = split(raw).answer

    private fun find(lower: String, from: Int, opens: Boolean): Match? =
        TAGS.asSequence()
            .filter { it.opens == opens }
            .mapNotNull { tag ->
                val index = lower.indexOf(tag.marker, from)
                if (index >= 0) Match(index, tag) else null
            }
            .minByOrNull { it.index }
}
