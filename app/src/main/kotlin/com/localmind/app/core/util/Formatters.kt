package com.localmind.app.core.util

import com.localmind.app.core.i18n.UiKey
import com.localmind.app.core.i18n.t

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex])
}

fun formatTokenCount(tokens: Int): String = when {
    tokens < 1000 -> tokens.toString()
    tokens < 10_000 -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
    else -> "${tokens / 1000}k"
}

/** One-line title derived from the first user message. */
fun deriveConversationTitle(text: String, maxLength: Int = 48): String {
    val firstLine = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return t(UiKey.actionNewChat)
    val cleaned = firstLine
        .removePrefix("#")
        .trim()
        .replace(Regex("\\s+"), " ")
    return if (cleaned.length <= maxLength) {
        cleaned
    } else {
        cleaned.take(maxLength).trimEnd().trimEnd(',', '.', ';', ':') + "…"
    }
}

enum class ConversationGroup(val labelKey: UiKey) {
    PINNED(UiKey.chatPinned),
    TODAY(UiKey.chatToday),
    YESTERDAY(UiKey.chatYesterday),
    PREVIOUS_7_DAYS(UiKey.chatPrevious7),
    OLDER(UiKey.chatOlder),
}

/**
 * Buckets a conversation for the history drawer. Pinned conversations always
 * live in their own group regardless of age.
 */
fun conversationGroup(
    updatedAt: Long,
    now: Long = System.currentTimeMillis(),
    pinned: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): ConversationGroup {
    if (pinned) return ConversationGroup.PINNED
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val date = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0 -> ConversationGroup.TODAY
        days == 1L -> ConversationGroup.YESTERDAY
        days < 7L -> ConversationGroup.PREVIOUS_7_DAYS
        else -> ConversationGroup.OLDER
    }
}

private val shortTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

fun formatTimestamp(timestamp: Long): String {
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val group = conversationGroup(timestamp)
    return when (group) {
        ConversationGroup.TODAY, ConversationGroup.PINNED -> shortTimeFormatter.format(dateTime)
        else -> shortDateFormatter.format(dateTime)
    }
}
