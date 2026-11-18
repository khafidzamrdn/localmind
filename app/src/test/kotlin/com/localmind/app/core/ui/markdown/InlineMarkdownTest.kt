package com.localmind.app.core.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {

    @Test
    fun `parses bold italic inline code and strikethrough`() {
        val spans = parseInlineSpans("Use **bold**, *italic*, `code` and ~~old~~.")

        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "italic" && it.italic })
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.any { it.text == "old" && it.strikethrough })

        val joined = spans.joinToString("") { it.text }
        assertEquals("Use bold, italic, code and old.", joined)
    }

    @Test
    fun `includes link target`() {
        val spans = parseInlineSpans("See [docs](https://example.com/page).")

        val link = spans.single { it.link != null }
        assertEquals("docs", link.text)
        assertEquals("https://example.com/page", link.link)
    }

    @Test
    fun `leaves snake_case identifiers intact`() {
        val spans = parseInlineSpans("call my_function_name now")

        assertEquals("call my_function_name now", spans.joinToString("") { it.text })
        assertTrue(spans.all { !it.italic })
    }

    @Test
    fun `unclosed emphasis is rendered literally`() {
        val spans = parseInlineSpans("2 * 3 = 6")

        assertEquals("2 * 3 = 6", spans.joinToString("") { it.text })
    }
}
