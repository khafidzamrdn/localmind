package com.localmind.app.core.tool

/**
 * Tiny, strict JSON reader used for tool calls and tool results.
 *
 * The app does not depend on a JSON library and the tool protocol is small, so
 * a focused parser keeps the code unit-testable on the JVM and avoids holding
 * output in an Android-only type.
 */
object MiniJson {

    /** Parses a JSON object and rejects trailing content. */
    fun parseObject(text: String): Map<String, Any?>? {
        return try {
            val parser = Parser(text)
            parser.skipWhitespace()
            if (parser.peek() != '{') return null
            val value = parser.parseObject()
            parser.skipWhitespace()
            if (!parser.atEnd()) return null
            value
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Escapes [value] as a JSON string, including the surrounding quotes. */
    fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (char in value) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> {
                    if (char < ' ') {
                        builder.append("\\u")
                        builder.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun peek(): Char = if (atEnd()) '\u0000' else text[index]

        fun skipWhitespace() {
            while (!atEnd() && text[index].isWhitespace()) index++
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected , or } at $index")
                }
            }
        }

        private fun parseValue(): Any? {
            return when (val char = peek()) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseArray()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    if (char == '-' || char.isDigit()) parseNumber() else {
                        throw IllegalArgumentException("Unexpected character $char at $index")
                    }
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected , or ] at $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val char = text[index++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("Unterminated escape")
                        when (val escape = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw IllegalArgumentException("Truncated unicode escape")
                                }
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape \\$escape")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            var isFloating = false
            if (!atEnd() && text[index] == '.') {
                isFloating = true
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                isFloating = true
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("Invalid number")
            return if (isFloating) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) {
                throw IllegalArgumentException("Invalid literal at $index")
            }
            index += literal.length
            return value
        }

        private fun expect(char: Char) {
            if (atEnd() || text[index] != char) {
                throw IllegalArgumentException("Expected $char at $index")
            }
            index++
        }
    }
}
