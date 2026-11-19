package com.localmind.app.core.tool

/**
 * Classifies a shell command before LocalMind hands it to Termux.
 *
 * [Risk.AUTO_READ] is a deliberately small allowlist of read-only commands.
 * Everything else needs a manual tap, and a short list of catastrophic
 * commands is refused even when the user taps Run.
 *
 * The classifier is conservative on purpose: a false REVIEW only costs one
 * tap, while a false AUTO_READ could change or leak data without consent.
 */
object CommandPolicy {

    enum class Risk { AUTO_READ, REVIEW, BLOCKED }

    data class Assessment(
        val risk: Risk,
        val reason: String? = null,
    ) {
        val canAutoRun: Boolean get() = risk == Risk.AUTO_READ
        val blocked: Boolean get() = risk == Risk.BLOCKED
    }

    /** Commands that only read state and cannot write as part of this app. */
    private val autoCommands = setOf(
        "pwd", "whoami", "id", "uname", "date", "uptime",
        "echo", "printf",
        "ls", "cat", "head", "tail", "wc",
        "grep", "egrep", "fgrep",
        "find", "stat", "file", "du", "df",
        "which", "type", "command",
        "basename", "dirname", "realpath", "readlink",
        "md5sum", "sha1sum", "sha256sum",
        "cut", "tr", "sort", "tree",
        "getprop", "termux-info", "ps", "git",
    )

    private val safeGitSubcommands = setOf(
        "status", "diff", "log", "show", "branch", "remote",
        "rev-parse", "ls-files", "describe", "blame", "shortlog", "grep",
    )

    private val unsafeFindFlags = listOf(
        "-exec", "-execdir", "-ok", "-okdir", "-delete",
        "-fprint", "-fprintf", "-fls",
    )

    private val sensitivePath = Regex(
        """(^|[\s/])\.env(\s|$)|\.ssh/|id_rsa|id_ed25519|\.gnupg|keystore|\.netrc|credentials""",
    )

    private val hardBlocks = listOf(
        Regex("""(^|[\s;|&(])sudo(\s|$)""") to "sudo is blocked.",
        Regex("""(^|[\s;|&(])su(\s|$)""") to "su is blocked.",
        Regex("""(^|[\s;|&])reboot(\s|$)""") to "Rebooting is blocked.",
        Regex("""(^|[\s;|&])shutdown(\s|$)""") to "Shutting down is blocked.",
        Regex("""(^|[\s;|&])poweroff(\s|$)""") to "Powering off is blocked.",
        Regex("""(^|[\s;|&])mkfs(\.\w+)?(\s|$)""") to "Formatting is blocked.",
        Regex("""(^|[\s;|&])dd\s+[^|;&]*of=/dev/""") to "Writing raw block devices is blocked.",
        Regex("""(^|[\s;|&])pm\s+(uninstall|clear)\b""") to "Uninstalling or clearing apps is blocked.",
        Regex("""(^|[\s;|&])settings\s+put\b""") to "Changing system settings is blocked.",
        Regex("""(^|[\s;|&])am\s+force-stop\b""") to "Force-stopping apps is blocked.",
        Regex("""\|\s*(sh|bash|zsh|dash|ash|python3?|perl|ruby|node)\b""") to
            "Piping into an interpreter is blocked.",
        Regex("""chmod\s+-r\s+777\s+/(\s|$)""") to
            "Making the whole filesystem writable is blocked.",
        Regex("""chown\s+-r\s+[^\s]+\s+/(\s|$)""") to
            "Changing ownership of the filesystem is blocked.",
        Regex(""":\(\)\s*\{""") to "Fork bombs are blocked.",
    )

    fun assess(raw: String): Assessment {
        val command = raw.trim()
        if (command.isEmpty()) return Assessment(Risk.REVIEW)
        if (command.length > ToolCallParser.MAX_COMMAND_LENGTH) {
            return Assessment(Risk.BLOCKED, "The command is too long.")
        }
        if (command.contains('\n') || command.contains('\r')) {
            return Assessment(Risk.BLOCKED, "Multi-line commands are not allowed.")
        }

        val lower = command.lowercase()
        hardBlocks.forEach { (pattern, reason) ->
            if (pattern.containsMatchIn(lower)) return Assessment(Risk.BLOCKED, reason)
        }
        if (isCatastrophicRm(lower)) {
            return Assessment(Risk.BLOCKED, "Deleting a protected directory is blocked.")
        }

        // Redirections, command substitution, chaining and background jobs can
        // hide a write or a second command. Only plain pipelines are considered.
        if (hasWriteOrExecSyntax(command)) return Assessment(Risk.REVIEW)
        if (sensitivePath.containsMatchIn(lower)) {
            return Assessment(Risk.REVIEW, "The command touches a potentially sensitive path.")
        }

        val segments = command.split('|')
        for (segment in segments) {
            val tokens = tokenize(segment)
            if (tokens.isEmpty()) return Assessment(Risk.REVIEW)
            val executable = tokens.first().substringAfterLast('/').lowercase()
            if (executable !in autoCommands) return Assessment(Risk.REVIEW)

            val flags = tokens.drop(1)
            when (executable) {
                "git" -> if (!isSafeGit(tokens)) return Assessment(Risk.REVIEW)
                "find" -> if (flags.any { flag -> unsafeFindFlags.any { flag.startsWith(it) } }) {
                    return Assessment(Risk.REVIEW)
                }
                "sort" -> if (flags.any { it == "-o" || it.startsWith("--output") }) {
                    return Assessment(Risk.REVIEW)
                }
                "tree" -> if (flags.any { it == "-o" || it.startsWith("--output") }) {
                    return Assessment(Risk.REVIEW)
                }
                "grep", "egrep", "fgrep" -> if (flags.any { it.startsWith("--pre") }) {
                    return Assessment(Risk.REVIEW)
                }
                "tail" -> if (flags.any { it == "-f" || it == "-F" || it.startsWith("--follow") }) {
                    return Assessment(Risk.REVIEW)
                }
                "date" -> {
                    if (flags.any { it == "-s" || it.startsWith("--set") }) {
                        return Assessment(Risk.REVIEW)
                    }
                    // A positional argument is a date to set; only +FORMAT is read-only.
                    if (flags.any { !it.startsWith("-") && !it.startsWith("+") }) {
                        return Assessment(Risk.REVIEW)
                    }
                }
                "command" -> if (tokens.getOrNull(1) !in setOf("-v", "-V")) {
                    return Assessment(Risk.REVIEW)
                }
            }
        }

        return Assessment(Risk.AUTO_READ)
    }

    private fun hasWriteOrExecSyntax(command: String): Boolean {
        if (command.contains('>') || command.contains('<')) return true
        if (command.contains('`')) return true
        if (command.contains("\$(") || command.contains("\${")) return true
        if (command.contains(';')) return true
        if (command.contains("&&") || command.contains("||") || command.contains("|&")) return true
        if (Regex("""(^|\s)&(\s|$)""").containsMatchIn(command)) return true
        if (command.contains('\u0000')) return true
        return false
    }

    private fun isSafeGit(tokens: List<String>): Boolean {
        if (tokens.size < 2) return true
        val subcommand = tokens[1].lowercase()
        if (subcommand !in safeGitSubcommands) return false

        val rest = tokens.drop(2)
        if (rest.any { it == "--output" || it.startsWith("--output=") || it == "-o" }) return false
        if (subcommand == "remote" && rest.isNotEmpty()) {
            val allowed = setOf("-v", "--verbose", "show", "get-url")
            if (rest.first() !in allowed) return false
        }
        if (subcommand == "branch") {
            val risky = setOf(
                "-d", "-D", "-m", "-M", "--delete", "--move", "--copy",
                "-c", "-C", "--edit-description",
            )
            if (rest.any { it in risky }) return false
            // `git branch <name>` creates a branch. Only list/filter forms are safe.
            val filters = setOf(
                "--list", "-l", "--contains", "--no-contains",
                "--merged", "--no-merged", "--points-at",
            )
            val hasFilter = rest.any { flag ->
                flag in filters || flag.startsWith("--contains=") ||
                    flag.startsWith("--no-contains=") || flag.startsWith("--merged=") ||
                    flag.startsWith("--no-merged=") || flag.startsWith("--points-at=")
            }
            val positionals = rest.filterNot { it.startsWith("-") }
            if (positionals.isNotEmpty() && !hasFilter) return false
        }
        return true
    }

    private fun isCatastrophicRm(lower: String): Boolean {
        if (!Regex("""(^|[\s;|&])rm(\s|$)""").containsMatchIn(lower)) return false
        val recursive = Regex("""(^|\s)-[a-zA-Z]*[rR][a-zA-Z]*(\s|$)""").containsMatchIn(lower) ||
            lower.contains("--recursive")
        if (!recursive) return false

        val normalized = lower.replace("\"", "").replace("'", "")
        if (Regex("""(^|\s)/data/data(/\S*)?(?=\s|$)""").containsMatchIn(normalized)) return true

        val protected = listOf(
            "/", "/*", ".", "./*", "*", "~", "~/*", "\$home", "\$home/*",
            "/data", "/data/*", "/sdcard", "/sdcard/*",
            "/storage", "/storage/*", "/system", "/system/*",
            "/vendor", "/vendor/*", "/odm", "/product",
        )
        return protected.any { target ->
            Regex("""(^|\s)${Regex.escape(target)}(?=\s|$)""").containsMatchIn(normalized)
        }
    }

    /** Splits on whitespace while honouring simple single/double quotes. */
    private fun tokenize(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (char in segment.trim()) {
            if (escaped) {
                current.append(char)
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null else current.append(char)
                continue
            }
            if (char == '\'' || char == '"') {
                quote = char
                continue
            }
            if (char.isWhitespace()) {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }
            } else {
                current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
