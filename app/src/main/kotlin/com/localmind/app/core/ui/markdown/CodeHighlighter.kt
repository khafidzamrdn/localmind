package com.localmind.app.core.ui.markdown

/**
 * Minimal, allocation-friendly syntax highlighting for fenced code blocks.
 * It is intentionally heuristic: chat code should be readable, not a full
 * language server.
 */
enum class CodeTokenType {
    PLAIN,
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    ANNOTATION,
}

data class CodeToken(val text: String, val type: CodeTokenType)

private val KOTLIN_KEYWORDS = setOf(
    "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
    "const", "constructor", "continue", "crossinline", "data", "delegate", "do", "dynamic",
    "else", "enum", "expect", "external", "false", "field", "file", "final", "finally", "for",
    "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface",
    "internal", "is", "lateinit", "noinline", "null", "object", "open", "operator", "out",
    "override", "package", "param", "private", "protected", "public", "reified", "return",
    "sealed", "set", "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias",
    "val", "var", "vararg", "when", "where", "while", "it",
)

private val BASH_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case",
    "esac", "function", "echo", "export", "local", "return", "exit", "source", "cd", "sudo",
)

private val GRADLE_KEYWORDS = setOf(
    "plugins", "android", "dependencies", "implementation", "testImplementation", "buildTypes",
    "defaultConfig", "compileSdk", "minSdk", "targetSdk", "versionCode", "versionName",
    "namespace", "applicationId", "repositories", "google", "mavenCentral", "id", "version",
)

private fun keywordsFor(language: String?): Set<String> = when (language?.lowercase()) {
    "kotlin", "kt", "kts", "java", "gradle", "groovy" -> KOTLIN_KEYWORDS + GRADLE_KEYWORDS
    "bash", "sh", "shell", "zsh" -> BASH_KEYWORDS
    "json", "yaml", "yml" -> setOf("true", "false", "null")
    else -> emptySet()
}

private fun lineCommentMarker(language: String?): String? = when (language?.lowercase()) {
    "kotlin", "kt", "kts", "java", "javascript", "js", "ts", "typescript", "c", "cpp",
    "swift", "go", "rust", "rs", "gradle", "groovy" -> "//"
    "bash", "sh", "shell", "zsh", "python", "py", "yaml", "yml", "toml" -> "#"
    "sql" -> "--"
    else -> null
}

fun highlightCode(code: String, language: String?): List<CodeToken> {
    val keywords = keywordsFor(language)
    val commentMarker = lineCommentMarker(language)
    val tokens = mutableListOf<CodeToken>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            tokens += CodeToken(plain.toString(), CodeTokenType.PLAIN)
            plain.clear()
        }
    }

    fun emit(type: CodeTokenType, start: Int, end: Int) {
        flushPlain()
        tokens += CodeToken(code.substring(start, end), type)
    }

    while (index < code.length) {
        val char = code[index]
        val rest = code.length - index

        when {
            commentMarker != null && rest >= commentMarker.length &&
                code.startsWith(commentMarker, index) -> {
                val end = code.indexOf('\n', index).let { if (it == -1) code.length else it }
                emit(CodeTokenType.COMMENT, index, end)
                index = end
            }

            rest >= 2 && code.startsWith("/*", index) -> {
                val end = code.indexOf("*/", index + 2).let { if (it == -1) code.length else it + 2 }
                emit(CodeTokenType.COMMENT, index, end)
                index = end
            }

            char == '"' || char == '\'' -> {
                var end = index + 1
                while (end < code.length) {
                    if (code[end] == '\\') {
                        end += 2
                        continue
                    }
                    if (code[end] == char) {
                        end++
                        break
                    }
                    end++
                }
                emit(CodeTokenType.STRING, index, end.coerceAtMost(code.length))
                index = end.coerceAtMost(code.length)
            }

            char.isDigit() -> {
                var end = index
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')) {
                    end++
                }
                emit(CodeTokenType.NUMBER, index, end)
                index = end
            }

            char == '@' && index + 1 < code.length && code[index + 1].isLetter() -> {
                var end = index + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                emit(CodeTokenType.ANNOTATION, index, end)
                index = end
            }

            char.isLetter() || char == '_' -> {
                var end = index
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                val word = code.substring(index, end)
                if (word in keywords) {
                    emit(CodeTokenType.KEYWORD, index, end)
                } else {
                    plain.append(word)
                }
                index = end
            }

            else -> {
                plain.append(char)
                index++
            }
        }
    }
    flushPlain()
    return tokens
}
