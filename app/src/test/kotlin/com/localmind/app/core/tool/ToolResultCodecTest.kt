package com.localmind.app.core.tool

import com.localmind.app.core.termux.TermuxRunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultCodecTest {

    private val call = ToolCall(
        name = ToolRegistry.TERMUX_RUN,
        arguments = mapOf("command" to "printf 'hi'"),
    )

    @Test
    fun `round trips output with quotes and newlines`() {
        val content = ToolResultCodec.encode(
            ToolResultContent(
                tool = ToolRegistry.TERMUX_RUN,
                status = ToolStatus.OK,
                exitCode = 0,
                command = "printf \"a\\nb\"",
                stdout = "a\nb \"quoted\"",
            ),
        )

        assertTrue(content.startsWith("<tool_response>"))
        assertTrue(content.endsWith("</tool_response>"))

        val decoded = ToolResultCodec.decode(content)
        assertNotNull(decoded)
        assertEquals(ToolStatus.OK, decoded?.status)
        assertEquals(0, decoded?.exitCode)
        assertEquals("printf \"a\\nb\"", decoded?.command)
        assertEquals("a\nb \"quoted\"", decoded?.stdout)
    }

    @Test
    fun `caps large output and notes truncation`() {
        val big = "x".repeat(ToolResultCodec.MAX_OUTPUT_CHARS + 100)
        val result = ToolResultCodec.fromRun(
            call,
            TermuxRunResult.Completed(exitCode = 0, stdout = big, stderr = ""),
        )

        assertEquals(ToolResultCodec.MAX_OUTPUT_CHARS, result.stdout.length)
        assertTrue(result.note?.contains("truncated") == true)
        assertEquals(ToolStatus.OK, result.status)
    }

    @Test
    fun `maps a failed run to an error result`() {
        val result = ToolResultCodec.fromRun(
            call,
            TermuxRunResult.Failed(message = "Termux is not installed.", hint = "Install it."),
        )

        assertEquals(ToolStatus.ERROR, result.status)
        assertEquals("Termux is not installed.", result.stderr)
        assertEquals("Install it.", result.note)
    }

    @Test
    fun `encodes a declined command`() {
        val decoded = ToolResultCodec.decode(ToolResultCodec.encode(ToolResultCodec.declined(call)))

        assertEquals(ToolStatus.DECLINED, decoded?.status)
        assertEquals("printf 'hi'", decoded?.command)
        assertTrue(decoded?.note?.contains("declined") == true)
    }

    @Test
    fun `round trips the auto-approved flag`() {
        val content = ToolResultCodec.encode(
            ToolResultContent(
                tool = ToolRegistry.TERMUX_RUN,
                status = ToolStatus.OK,
                exitCode = 0,
                command = "ls",
                stdout = "app\n",
                auto = true,
            ),
        )

        val decoded = ToolResultCodec.decode(content)
        assertEquals(ToolStatus.OK, decoded?.status)
        assertEquals(true, decoded?.auto)
    }

    @Test
    fun `an explicit approval is not marked auto`() {
        val result = ToolResultCodec.fromRun(
            call,
            TermuxRunResult.Completed(exitCode = 0, stdout = "hi", stderr = ""),
        )
        val decoded = ToolResultCodec.decode(ToolResultCodec.encode(result))

        assertEquals(false, decoded?.auto)
    }

    @Test
    fun `fromRun carries the auto flag`() {
        val result = ToolResultCodec.fromRun(
            call,
            TermuxRunResult.Completed(exitCode = 0, stdout = "hi", stderr = ""),
            auto = true,
        )

        assertTrue(result.auto)
        assertTrue(ToolResultCodec.decode(ToolResultCodec.encode(result))?.auto == true)
    }

    @Test
    fun `encodes a blocked command without running it`() {
        val decoded = ToolResultCodec.decode(
            ToolResultCodec.encode(ToolResultCodec.blocked(call, "sudo is blocked.")),
        )

        assertEquals(ToolStatus.BLOCKED, decoded?.status)
        assertEquals("printf 'hi'", decoded?.command)
        assertEquals("sudo is blocked.", decoded?.note)
        assertEquals(null, decoded?.exitCode)
        assertEquals("", decoded?.stdout)
    }
}
