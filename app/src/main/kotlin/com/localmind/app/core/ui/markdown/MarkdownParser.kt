package com.localmind.app.core.ui.markdown

/**
 * A deliberately small Markdown subset tailored to chat output. Parsing is pure
 * Kotlin so it can be unit-tested on the JVM and reused outside Compose.
 *
 * Supported: headings, paragraphs, fenced code, unordered/ordered lists,
 * block quotes, horizontal rules and simple tables. Raw HTML is rendered as
 * text, never interpreted.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** [closed] is false while a fenced block is still streaming. */
    data class Code(val language: String?, val code: String, val closed: Boolean) : MarkdownBlock

    data class Quote(val text: String) : MarkdownBlock

    data class ListBlock(val items: List<MarkdownListItem>) : MarkdownBlock

    data object Divider : MarkdownBlock

    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

data class MarkdownListItem(
    val text: String,
    val ordered: Boolean,
    val number: Int,
    val indent: Int,
)

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.*)$""")
private val LIST_ITEM_REGEX = Regex("""^(\s*)([-*+]|\d+\.)\s+(.*)$""")
private val TABLE_SEPARATOR_REGEX = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")

fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> {
                index++
            }

            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim().ifBlank { null }
                val codeLines = mutableListOf<String>()
                var closed = false
                index++
                while (index < lines.size) {
                    if (lines[index].trim().startsWith("```")) {
                        closed = true
                        index++
                        break
                    }
                    codeLines += lines[index]
                    index++
                }
                blocks += MarkdownBlock.Code(language, codeLines.joinToString("\n"), closed)
            }

            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                blocks += MarkdownBlock.Divider
                index++
            }

            HEADING_REGEX.matches(trimmed) -> {
                val match = HEADING_REGEX.matchEntire(trimmed)!!
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                )
                index++
            }

            trimmed.startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quote += lines[index].trim().removePrefix(">").removePrefix(" ").trim()
                    index++
                }
                blocks += MarkdownBlock.Quote(quote.joinToString("\n").trim())
            }

            LIST_ITEM_REGEX.matches(line) -> {
                val items = mutableListOf<MarkdownListItem>()
                while (index < lines.size && LIST_ITEM_REGEX.matches(lines[index])) {
                    val match = LIST_ITEM_REGEX.matchEntire(lines[index])!!
                    val marker = match.groupValues[2]
                    val ordered = marker.endsWith(".")
                    items += MarkdownListItem(
                        text = match.groupValues[3].trim(),
                        ordered = ordered,
                        number = if (ordered) marker.dropLast(1).toIntOrNull() ?: 1 else 0,
                        indent = match.groupValues[1].replace("\t", "  ").length / 2,
                    )
                    index++
                }
                blocks += MarkdownBlock.ListBlock(items)
            }

            isTableStart(lines, index) -> {
                val headers = splitTableRow(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size) {
                    val rowLine = lines[index]
                    if (rowLine.isBlank() || !rowLine.contains('|')) break
                    rows += splitTableRow(rowLine)
                    index++
                }
                blocks += MarkdownBlock.Table(headers, rows)
            }

            else -> {
                val paragraphLines = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index]
                    if (candidate.isBlank()) break
                    if (paragraphLines.isNotEmpty() && isBlockStart(candidate)) break
                    paragraphLines += candidate.trim()
                    index++
                }
                blocks += MarkdownBlock.Paragraph(paragraphLines.joinToString(" "))
            }
        }
    }
    return blocks
}

private fun isTableStart(lines: List<String>, index: Int): Boolean {
    val line = lines.getOrNull(index) ?: return false
    val separator = lines.getOrNull(index + 1) ?: return false
    return line.contains('|') && TABLE_SEPARATOR_REGEX.matches(separator)
}

private fun isBlockStart(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("```") ||
        trimmed.startsWith(">") ||
        trimmed == "---" ||
        trimmed == "***" ||
        trimmed == "___" ||
        HEADING_REGEX.matches(trimmed) ||
        LIST_ITEM_REGEX.matches(line)
}

internal fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
