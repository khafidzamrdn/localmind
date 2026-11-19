package com.localmind.app.core.termux

/** Everything the UI needs to describe the Termux bridge before running anything. */
data class TermuxStatus(
    val installed: Boolean = false,
    val versionName: String? = null,
    val versionSupportsResults: Boolean = false,
    val permissionGranted: Boolean = false,
) {
    val ready: Boolean get() = installed && versionSupportsResults && permissionGranted

    val headline: String
        get() = when {
            !installed -> "Termux not installed"
            !versionSupportsResults -> "Termux is too old"
            !permissionGranted -> "Permission needed"
            else -> "Ready"
        }

    val summary: String
        get() = when {
            !installed ->
                "Install Termux (com.termux) to let LocalMind run shell commands on this device."
            !versionSupportsResults ->
                "Termux $MINIMUM_VERSION or newer is required so command output can be returned."
            !permissionGranted ->
                "Allow “Run commands in Termux” for LocalMind in Android Settings → Apps → LocalMind → " +
                    "Permissions → Additional permissions."
            else ->
                "Termux ${versionName ?: ""} is ready. Termux must also have allow-external-apps=true; " +
                    "that is checked when you run a command."
        }

    companion object {
        const val MINIMUM_VERSION = TermuxConstants.MIN_RESULT_VERSION
    }
}

/** Dotted-version comparison without pulling in a dependency. */
object TermuxVersion {
    fun atLeast(current: String?, minimum: String): Boolean {
        if (current == null) return false
        val left = current.split('.', '-', '+')
        val right = minimum.split('.', '-', '+')
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrNull(index)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            val b = right.getOrNull(index)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return true
    }
}

/** Outcome of one command handed to Termux. */
sealed interface TermuxRunResult {

    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean = false,
        val stderrTruncated: Boolean = false,
        val durationMs: Long = 0,
    ) : TermuxRunResult {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** The command never reached a shell, or Termux reported an internal error. */
    data class Failed(
        val message: String,
        val hint: String? = null,
    ) : TermuxRunResult

    /** LocalMind stopped waiting. The command may still be running inside Termux. */
    data class TimedOut(
        val timeoutMs: Long,
        val hint: String? = null,
    ) : TermuxRunResult
}

/** Raw bundle payload received from Termux's pending intent. */
internal data class TermuxRawResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val errorCode: Int,
    val errorMessage: String?,
    val stdoutOriginalLength: Int?,
    val stderrOriginalLength: Int?,
)
