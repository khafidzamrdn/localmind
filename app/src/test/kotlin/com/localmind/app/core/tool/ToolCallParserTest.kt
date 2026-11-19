package com.localmind.app.core.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun `parses a qwen style call and hides the block`() {
        val text = """
            I will inspect the home directory.

            <tool_call>
            {"name": "termux_run", "arguments": {"command": "ls -la ~", "purpose": "list files"}}
            </tool_call>
        """.trimIndent()

        val split = ToolCallParser.split(text)

        assertEquals("I will inspect the home directory.", split.visibleText)
        assertEquals("termux_run", split.call?.name)
        assertEquals("ls -la ~", split.call?.command)
        assertEquals("list files", split.call?.purpose)
        assertFalse(split.malformed)
        assertFalse(split.incomplete)
    }

    @Test
    fun `decodes escaped characters in the command`() {
        val text = """
            <tool_call>
            {"name":"termux_run","arguments":{"command":"printf \"a\nb\"","purpose":"test"}}
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.split(text).call

        assertEquals("printf \"a\nb\"", call?.command)
    }

    @Test
    fun `keeps prose after the block`() {
        val text = "Before\n<tool_call>\n" +
            "{\"name\":\"termux_run\",\"arguments\":{\"command\":\"pwd\"}}\n" +
            "</tool_call>\nAfter"

        val split = ToolCallParser.split(text)

        assertEquals("Before\n\nAfter", split.visibleText)
        assertEquals("pwd", split.call?.command)
    }

    @Test
    fun `rejects a call without a command`() {
        val split = ToolCallParser.split(
            "<tool_call>\n{\"name\":\"termux_run\",\"arguments\":{}}\n</tool_call>",
        )

        assertNull(split.call)
        assertTrue(split.malformed)
        assertTrue(split.visibleText.isEmpty())
    }

    @Test
    fun `marks an unfinished block as incomplete`() {
        val split = ToolCallParser.split(
            "Checking.\n<tool_call>\n{\"name\":\"termux_run\",\"arguments\":{\"command\":\"ls\"}",
        )

        assertNull(split.call)
        assertTrue(split.incomplete)
        assertEquals("Checking.", split.visibleText)
    }

    @Test
    fun `ignores a tool block inside text when there is no valid call`() {
        val split = ToolCallParser.split("no call here")

        assertNull(split.call)
        assertFalse(split.hasBlock)
        assertEquals("no call here", split.visibleText)
    }

    @Test
    fun `strips every block and uses the last call`() {
        val text = "A<tool_call>{\"name\":\"termux_run\",\"arguments\":{\"command\":\"pwd\"}}</tool_call>" +
            "B<tool_call>{\"name\":\"termux_run\",\"arguments\":{\"command\":\"ls\"}}</tool_call>C"

        val split = ToolCallParser.split(text)

        assertEquals("ABC", split.visibleText)
        assertEquals("ls", split.call?.command)
    }

    @Test
    fun `does not execute an example inside a code fence`() {
        val text = """
            Here is the format:

            ```text
            <tool_call>
            {"name": "termux_run", "arguments": {"command": "rm -rf ~"}}
            </tool_call>
            ```

            I can use it when you ask.
        """.trimIndent()

        val split = ToolCallParser.split(text)

        assertNull(split.call)
        assertFalse(split.hasBlock)
        assertTrue(split.visibleText.contains("rm -rf ~"))
    }
}
