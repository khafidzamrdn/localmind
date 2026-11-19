package com.localmind.app.core.tool

/** One validated tool invocation proposed by the model. */
data class ToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
) {
    val command: String? get() = arguments["command"] as? String
    val purpose: String? get() = (arguments["purpose"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Splits an assistant reply into the part to show and, when present, the
 * `<tool_call>…</tool_call>` block the model emitted.
 *
 * [malformed] is true when a block exists but does not contain a usable call
 * (for example, truncated output). Malformed blocks are still hidden from the
 * Markdown answer so raw protocol tags never leak into the chat.
 */
data class ToolCallSplit(
    val visibleText: String,
    val call: ToolCall?,
    val rawBlock: String?,
    val malformed: Boolean = false,
    val incomplete: Boolean = false,
) {
    val hasBlock: Boolean get() = rawBlock != null || incomplete
}

object ToolCallParser {

    const val OPEN_TAG = "<tool_call>"
    const val CLOSE_TAG = "</tool_call>"

    /** Maximum accepted command length before the user is asked to run it. */
    const val MAX_COMMAND_LENGTH = 4_000

    fun split(text: String): ToolCallSplit {
        data class Block(val start: Int, val end: Int, val fenced: Boolean)

        val blocks = mutableListOf<Block>()
        var incompleteAt = -1
        var searchFrom = 0

        while (true) {
            val open = text.indexOf(OPEN_TAG, searchFrom, ignoreCase = true)
            if (open < 0) break
            val fenced = isInsideCodeFence(text, open)
            val close = text.indexOf(CLOSE_TAG, open + OPEN_TAG.length, ignoreCase = true)
            if (close < 0) {
                // Streaming or truncated output: hide the unfinished protocol block.
                if (!fenced) incompleteAt = open
                break
            }
            blocks += Block(open, close + CLOSE_TAG.length, fenced)
            searchFrom = close + CLOSE_TAG.length
        }

        // Fenced examples are documentation, not calls: keep them visible and
        // never offer them for execution.
        val protocolBlocks = blocks.filterNot { it.fenced }

        val removals = buildList {
            protocolBlocks.forEach { add(it.start to it.end) }
            if (incompleteAt >= 0) add(incompleteAt to text.length)
        }.sortedBy { it.first }

        val visible = buildString {
            var cursor = 0
            removals.forEach { (start, end) ->
                if (start >= cursor) {
                    append(text, cursor, start)
                    cursor = end
                }
            }
            if (cursor < text.length) append(text, cursor, text.length)
        }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        val lastBlock = protocolBlocks.lastOrNull()
            ?: return ToolCallSplit(
                visibleText = visible,
                call = null,
                rawBlock = if (incompleteAt >= 0) text.substring(incompleteAt) else null,
                malformed = incompleteAt >= 0,
                incomplete = incompleteAt >= 0,
            )

        val raw = text.substring(lastBlock.start, lastBlock.end)
        val call = parseBlock(raw)

        return ToolCallSplit(
            visibleText = visible,
            call = call,
            rawBlock = raw,
            malformed = call == null,
        )
    }

    /** True when [position] sits between an odd number of triple-backtick fences. */
    private fun isInsideCodeFence(text: String, position: Int): Boolean {
        var fences = 0
        var index = 0
        while (true) {
            val tick = text.indexOf("```", index)
            if (tick < 0 || tick >= position) break
            fences++
            index = tick + 3
        }
        return fences % 2 == 1
    }

    private fun parseBlock(block: String): ToolCall? {
        // Read between the tags without assuming the exact tag casing.
        val innerStart = block.indexOf('>').let { if (it >= 0) it + 1 else 0 }
        val innerEnd = block.lastIndexOf('<').let { if (it >= innerStart) it else block.length }
        val inner = block.substring(innerStart, innerEnd).trim()

        val json = extractJsonObject(inner) ?: return null
        val obj = MiniJson.parseObject(json) ?: return null
        val name = (obj["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val arguments = obj["arguments"] as? Map<*, *> ?: return null
        val map = arguments.entries.associate { (key, value) -> key.toString() to value }

        if (name == ToolRegistry.TERMUX_RUN) {
            val command = (map["command"] as? String)?.trim()
            if (command.isNullOrEmpty()) return null
            if (command.length > MAX_COMMAND_LENGTH) return null
        }

        return ToolCall(name = name, arguments = map)
    }

    /** Finds the outermost JSON object even if the model added stray prose. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    /** Removes every tool block so copied text contains only the answer. */
    fun strip(text: String): String = split(text).visibleText
}
