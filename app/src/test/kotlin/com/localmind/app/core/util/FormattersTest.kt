package com.localmind.app.core.util

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `formats file sizes`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", formatFileSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `derives a compact conversation title`() {
        assertEquals("Explain Kotlin coroutines", deriveConversationTitle("Explain Kotlin coroutines"))
        assertEquals("New chat", deriveConversationTitle("   \n  "))
        assertEquals("Heading", deriveConversationTitle("# Heading"))
    }

    @Test
    fun `truncates long titles with an ellipsis`() {
        val title = deriveConversationTitle("a".repeat(80), maxLength = 10)

        assertEquals(11, title.length)
        assertEquals("a".repeat(10) + "…", title)
    }

    @Test
    fun `groups conversations by calendar day`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        fun at(year: Int, month: Int, day: Int): Long =
            ZonedDateTime.of(year, month, day, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(ConversationGroup.PINNED, conversationGroup(at(2020, 1, 1), now, pinned = true, zone = zone))
        assertEquals(ConversationGroup.TODAY, conversationGroup(at(2026, 3, 10), now, zone = zone))
        assertEquals(ConversationGroup.YESTERDAY, conversationGroup(at(2026, 3, 9), now, zone = zone))
        assertEquals(ConversationGroup.PREVIOUS_7_DAYS, conversationGroup(at(2026, 3, 5), now, zone = zone))
        assertEquals(ConversationGroup.OLDER, conversationGroup(at(2026, 2, 20), now, zone = zone))
    }

    @Test
    fun `formats token counts`() {
        assertEquals("42", formatTokenCount(42))
        assertEquals("1.2k", formatTokenCount(1234))
        assertEquals("12k", formatTokenCount(12_300))
    }
}
