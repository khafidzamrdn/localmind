package com.localmind.app.core.tool

import com.localmind.app.core.termux.TermuxRunResult

enum class ToolStatus(val label: String) {
    OK("ok"),
    ERROR("error"),
    TIMEOUT("timeout"),
    DECLINED("declined"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String?): ToolStatus =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: ERROR
    }
}

/** Structured result persisted as a TOOL message and shown as a terminal card. */
data class ToolResultContent(
    val tool: String,
    val status: ToolStatus,
    val exitCode: Int? = null,
    val command: String,
    val stdout: String = "",
    val stderr: String = "",
    val note: String? = null,
) {
    val isSuccess: Boolean get() = status == ToolStatus.OK
}

/**
 * Encodes tool results as a JSON object wrapped in `<tool_response>`.
 *
 * The wrapper matches the convention Qwen-style chat templates use for tool
 * replies. The message is sent to the model with the user role so templates
 * that do not know a "tool" role still render it correctly.
 */
object ToolResultCodec {

    const val OPEN_TAG = "<tool_response>"
    const val CLOSE_TAG = "</tool_response>"

    /** Per-stream cap so one command cannot flood the context window. */
    const val MAX_OUTPUT_CHARS = 2_000

    fun encode(result: ToolResultContent): String {
        val json = buildString {
            append('{')
            appendField("tool", result.tool)
            append(',')
            appendField("status", result.status.label)
            if (result.exitCode != null) {
                append(',')
                append("\"exit_code\":").append(result.exitCode)
            }
            append(',')
            appendField("command", result.command)
            if (result.stdout.isNotEmpty()) {
                append(',')
                appendField("stdout", result.stdout)
            }
            if (result.stderr.isNotEmpty()) {
                append(',')
                appendField("stderr", result.stderr)
            }
            if (!result.note.isNullOrBlank()) {
                append(',')
                appendField("note", result.note)
            }
            append('}')
        }
        return "$OPEN_TAG\n$json\n$CLOSE_TAG"
    }

    fun decode(content: String): ToolResultContent? {
        val trimmed = content.trim()
        val inner = when {
            trimmed.startsWith(OPEN_TAG) && trimmed.endsWith(CLOSE_TAG) ->
                trimmed.removePrefix(OPEN_TAG).removeSuffix(CLOSE_TAG).trim()
            else -> trimmed
        }
        val obj = MiniJson.parseObject(inner) ?: return null
        val tool = (obj["tool"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val command = obj["command"] as? String ?: ""
        return ToolResultContent(
            tool = tool,
            status = ToolStatus.from(obj["status"] as? String),
            exitCode = (obj["exit_code"] as? Number)?.toInt(),
            command = command,
            stdout = obj["stdout"] as? String ?: "",
            stderr = obj["stderr"] as? String ?: "",
            note = obj["note"] as? String,
        )
    }

    fun fromRun(call: ToolCall, result: TermuxRunResult): ToolResultContent {
        return when (result) {
            is TermuxRunResult.Completed -> {
                val stdout = truncate(result.stdout)
                val stderr = truncate(result.stderr)
                val notes = buildList {
                    if (result.stdoutTruncated || stdout.length < result.stdout.length) {
                        add("stdout truncated to $MAX_OUTPUT_CHARS characters")
                    }
                    if (result.stderrTruncated || stderr.length < result.stderr.length) {
                        add("stderr truncated to $MAX_OUTPUT_CHARS characters")
                    }
                }
                ToolResultContent(
                    tool = call.name,
                    status = if (result.exitCode == 0) ToolStatus.OK else ToolStatus.ERROR,
                    exitCode = result.exitCode,
                    command = call.command.orEmpty(),
                    stdout = stdout,
                    stderr = stderr,
                    note = notes.joinToString("; ").takeIf { it.isNotEmpty() },
                )
            }

            is TermuxRunResult.Failed -> ToolResultContent(
                tool = call.name,
                status = ToolStatus.ERROR,
                command = call.command.orEmpty(),
                stderr = result.message,
                note = result.hint,
            )

            is TermuxRunResult.TimedOut -> ToolResultContent(
                tool = call.name,
                status = ToolStatus.TIMEOUT,
                command = call.command.orEmpty(),
                note = "LocalMind stopped waiting after ${result.timeoutMs / 1000}s. ${result.hint.orEmpty()}"
                    .trim(),
            )
        }
    }

    fun declined(call: ToolCall): ToolResultContent = ToolResultContent(
        tool = call.name,
        status = ToolStatus.DECLINED,
        command = call.command.orEmpty(),
        note = "The user declined to run this command.",
    )

    fun cancelled(call: ToolCall): ToolResultContent = ToolResultContent(
        tool = call.name,
        status = ToolStatus.CANCELLED,
        command = call.command.orEmpty(),
        note = "Generation was stopped before a result was returned. The command may still be running in Termux.",
    )

    private fun truncate(value: String): String {
        if (value.length <= MAX_OUTPUT_CHARS) return value
        return value.take(MAX_OUTPUT_CHARS)
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(MiniJson.quote(name)).append(':').append(MiniJson.quote(value))
    }
}
