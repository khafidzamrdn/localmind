package com.localmind.app.core.ui.markdown

/**
 * Inline Markdown spans. The UI maps these to Compose [androidx.compose.ui.text.AnnotatedString]
 * styles; keeping the scanner pure makes it cheap to test.
 */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

private val LINK_REGEX = Regex("""^\[([^\]]+)\]\(([^)\s]+)\)""")

fun parseInlineSpans(source: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += InlineSpan(plain.toString())
            plain.clear()
        }
    }

    while (index < source.length) {
        val char = source[index]
        when {
            char == '`' -> {
                val closing = source.indexOf('`', startIndex = index + 1)
                if (closing > index + 1) {
                    flushPlain()
                    spans += InlineSpan(source.substring(index + 1, closing), code = true)
                    index = closing + 1
                } else {
                    plain.append(char)
                    index++
                }
            }

            char == '[' -> {
                val match = LINK_REGEX.find(source.substring(index))
                if (match != null) {
                    flushPlain()
                    spans += InlineSpan(match.groupValues[1], link = match.groupValues[2])
                    index += match.value.length
                } else {
                    plain.append(char)
                    index++
                }
            }

            source.startsWith("**", index) || source.startsWith("__", index) -> {
                val marker = source.substring(index, index + 2)
                val closing = source.indexOf(marker, startIndex = index + 2)
                if (closing > index + 2) {
                    flushPlain()
                    spans += InlineSpan(source.substring(index + 2, closing), bold = true)
                    index = closing + 2
                } else {
                    plain.append(marker)
                    index += 2
                }
            }

            source.startsWith("~~", index) -> {
                val closing = source.indexOf("~~", startIndex = index + 2)
                if (closing > index + 2) {
                    flushPlain()
                    spans += InlineSpan(source.substring(index + 2, closing), strikethrough = true)
                    index = closing + 2
                } else {
                    plain.append("~~")
                    index += 2
                }
            }

            char == '*' || (char == '_' && isWordBoundary(source, index)) -> {
                val closing = source.indexOf(char, startIndex = index + 1)
                if (closing > index + 1) {
                    flushPlain()
                    spans += InlineSpan(source.substring(index + 1, closing), italic = true)
                    index = closing + 1
                } else {
                    plain.append(char)
                    index++
                }
            }

            else -> {
                plain.append(char)
                index++
            }
        }
    }
    flushPlain()
    return spans
}

private fun isWordBoundary(source: String, index: Int): Boolean {
    if (index == 0) return true
    val previous = source[index - 1]
    return !previous.isLetterOrDigit()
}
