package com.localmind.app.core.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings and paragraphs`() {
        val blocks = parseMarkdown("# Title\n\nHello world")

        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Hello world"), blocks[1])
    }

    @Test
    fun `closed code fence keeps language and code`() {
        val source = """
            Before

            ```kotlin
            val x = 1
            println(x)
            ```

            After
        """.trimIndent()

        val blocks = parseMarkdown(source)
        val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertTrue(code.closed)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `unclosed fence is treated as streaming code`() {
        val blocks = parseMarkdown("```kotlin\nval answer = 42")

        val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertFalse(code.closed)
        assertEquals("val answer = 42", code.code)
    }

    @Test
    fun `parses unordered and ordered lists`() {
        val blocks = parseMarkdown("- alpha\n- beta\n1. one\n2. two")

        val list = blocks.filterIsInstance<MarkdownBlock.ListBlock>().single()
        assertEquals(4, list.items.size)
        assertFalse(list.items[0].ordered)
        assertTrue(list.items[2].ordered)
        assertEquals(1, list.items[2].number)
    }

    @Test
    fun `parses a simple table`() {
        val source = "| Name | Value |\n| --- | --- |\n| a | 1 |\n| b | 2 |"

        val table = parseMarkdown(source).filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("Name", "Value"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("b", "2"), table.rows[1])
    }

    @Test
    fun `parses quotes and dividers`() {
        val blocks = parseMarkdown("> local first\n> no cloud\n\n---")

        assertEquals(MarkdownBlock.Quote("local first\nno cloud"), blocks[0])
        assertEquals(MarkdownBlock.Divider, blocks[1])
    }
}
