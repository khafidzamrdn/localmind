package com.localmind.app.core.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one shell command through Termux's RUN_COMMAND service and waits for the
 * result bundle Termux sends back.
 *
 * Nothing is executed by LocalMind itself: the command runs under Termux's UID
 * and needs both `com.termux.permission.RUN_COMMAND` (granted to LocalMind) and
 * `allow-external-apps=true` inside Termux.
 */
class TermuxBridge(private val context: Context) {

    private val appContext: Context = context.applicationContext

    fun status(): TermuxStatus {
        val packageInfo = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(TermuxConstants.TERMUX_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        if (packageInfo == null) return TermuxStatus()

        val versionName = packageInfo.versionName
        val permissionGranted = appContext.checkSelfPermission(TermuxConstants.PERMISSION_RUN_COMMAND) ==
            PackageManager.PERMISSION_GRANTED

        return TermuxStatus(
            installed = true,
            versionName = versionName,
            versionSupportsResults = TermuxVersion.atLeast(versionName, TermuxStatus.MINIMUM_VERSION),
            permissionGranted = permissionGranted,
        )
    }

    /**
     * Executes [command] with `bash -c` inside Termux and returns its output.
     *
     * [timeoutMs] only bounds how long LocalMind waits. Termux keeps running the
     * command after that; the eventual result is discarded.
     */
    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        label: String = "LocalMind",
    ): TermuxRunResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return@withContext TermuxRunResult.Failed("The command is empty.")
        }
        if (trimmed.length > TermuxConstants.MAX_COMMAND_CHARS) {
            return@withContext TermuxRunResult.Failed(
                "The command is too long (${trimmed.length} characters).",
                "Keep a single command under ${TermuxConstants.MAX_COMMAND_CHARS} characters.",
            )
        }

        val status = status()
        if (!status.installed) {
            return@withContext TermuxRunResult.Failed(
                "Termux is not installed.",
                "Install Termux, then grant LocalMind the “Run commands in Termux” permission.",
            )
        }
        if (!status.versionSupportsResults) {
            return@withContext TermuxRunResult.Failed(
                "Termux ${status.versionName ?: ""} is too old.",
                "Update Termux to ${TermuxStatus.MINIMUM_VERSION} or newer.",
            )
        }
        if (!status.permissionGranted) {
            return@withContext TermuxRunResult.Failed(
                "LocalMind is not allowed to run commands in Termux.",
                PERMISSION_HINT,
            )
        }

        val executionId = nextExecutionId()
        val deferred = TermuxResultDispatcher.register(executionId)
        val startedAt = SystemClock.elapsedRealtime()

        try {
            val resultIntent = Intent(appContext, TermuxResultReceiver::class.java)
                .setData(Uri.parse("localmind://termux/result/$executionId"))
                .putExtra(TermuxConstants.EXTRA_EXECUTION_ID, executionId)

            val flags = PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val resultPendingIntent = PendingIntent.getBroadcast(
                appContext,
                executionId,
                resultIntent,
                flags,
            )

            val intent = Intent().apply {
                setClassName(TermuxConstants.TERMUX_PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE)
                action = TermuxConstants.ACTION_RUN_COMMAND
                putExtra(TermuxConstants.EXTRA_COMMAND_PATH, TermuxConstants.TERMUX_BASH)
                putExtra(TermuxConstants.EXTRA_ARGUMENTS, arrayOf("-c", trimmed))
                putExtra(TermuxConstants.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME)
                putExtra(TermuxConstants.EXTRA_RUNNER, TermuxConstants.RUNNER_APP_SHELL)
                putExtra(TermuxConstants.EXTRA_BACKGROUND, true)
                putExtra(TermuxConstants.EXTRA_COMMAND_LABEL, label.take(80))
                putExtra(
                    TermuxConstants.EXTRA_COMMAND_DESCRIPTION,
                    "Command proposed in LocalMind. Review it before running.",
                )
                putExtra(TermuxConstants.EXTRA_PENDING_INTENT, resultPendingIntent)
            }

            try {
                appContext.startService(intent)
            } catch (security: SecurityException) {
                return@withContext TermuxRunResult.Failed(
                    "Android blocked the request to Termux.",
                    PERMISSION_HINT,
                )
            } catch (state: IllegalStateException) {
                return@withContext TermuxRunResult.Failed(
                    "Android did not allow starting Termux right now.",
                    "Keep LocalMind open while a command runs, then try again.",
                )
            }

            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
            val duration = SystemClock.elapsedRealtime() - startedAt

            if (raw == null) {
                return@withContext TermuxRunResult.TimedOut(
                    timeoutMs = timeoutMs,
                    hint = "The command may still be running inside Termux. Check the Termux notification.",
                )
            }

            if (raw.errorCode != TermuxConstants.NO_INTERNAL_ERROR) {
                val message = raw.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "Termux could not run the command."
                val hint = if (message.contains("allow-external-apps", ignoreCase = true)) {
                    ALLOW_EXTERNAL_APPS_HINT
                } else {
                    null
                }
                return@withContext TermuxRunResult.Failed(message.trim(), hint)
            }

            val stdout = raw.stdout.take(MAX_STREAM_CHARS)
            val stderr = raw.stderr.take(MAX_STREAM_CHARS)
            TermuxRunResult.Completed(
                exitCode = raw.exitCode ?: -1,
                stdout = stdout,
                stderr = stderr,
                stdoutTruncated = raw.stdout.length > MAX_STREAM_CHARS,
                stderrTruncated = raw.stderr.length > MAX_STREAM_CHARS,
                durationMs = duration,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TermuxRunResult.Failed(
                error.message ?: "The Termux request failed.",
                "Check that Termux is installed, updated, and allowed to run external commands.",
            )
        } finally {
            TermuxResultDispatcher.forget(executionId)
        }
    }

    private fun nextExecutionId(): Int = EXECUTION_ID.getAndIncrement()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_STREAM_CHARS = 48_000

        const val PERMISSION_HINT =
            "Android Settings → Apps → LocalMind → Permissions → Additional permissions → " +
                "“Run commands in Termux”."
        const val ALLOW_EXTERNAL_APPS_HINT =
            "Inside Termux run: echo 'allow-external-apps=true' >> ~/.termux/termux.properties, " +
                "then force-stop and reopen Termux."

        private val EXECUTION_ID = AtomicInteger(
            (System.currentTimeMillis() % 100_000L).toInt() * 10,
        )
    }
}
