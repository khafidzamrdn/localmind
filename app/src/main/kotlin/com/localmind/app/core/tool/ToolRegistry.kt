package com.localmind.app.core.tool

/**
 * Tools LocalMind may offer to the model.
 *
 * There is exactly one tool today: a shell command in Termux. It is described
 * to the model in plain text and every call must be approved in the chat UI,
 * so a hallucinated call can never execute silently.
 */
object ToolRegistry {

    const val TERMUX_RUN = "termux_run"

    const val SYSTEM_PROMPT = """
# Tools

You may call one or more functions to assist with the user query. You are
provided with function signatures within <tools></tools> XML tags:
<tools>
{"type":"function","function":{"name":"termux_run","description":"Run one shell command in the user's Termux environment on this Android device. The working directory is ~. The user must approve every call.","parameters":{"type":"object","properties":{"command":{"type":"string","description":"The shell command to run."},"purpose":{"type":"string","description":"One short sentence describing why this command helps."}},"required":["command"]}}}
</tools>

For each function call, return a json object with function name and arguments
within <tool_call></tool_call> XML tags:
<tool_call>
{"name": "termux_run", "arguments": {"command": "ls -la ~", "purpose": "list the home directory"}}
</tool_call>

Rules:
- Return at most one tool call per reply and put nothing after the block.
- Never claim a command ran, and never invent its output. Wait for the tool
  result message that LocalMind adds after the user approves the call.
- Prefer read-only commands unless the user explicitly asked to change files.
- Keep commands short and non-interactive. Never start servers or long jobs.
- Never use sudo, su, package upgrades, or destructive commands.
- If no tool is needed, answer normally in Markdown.
"""

    /** Short description used by the settings screen. */
    const val TERMUX_RUN_SUMMARY =
        "Propose one shell command in Termux. LocalMind asks for your approval before anything runs."

    val all: List<String> = listOf(TERMUX_RUN)
}
