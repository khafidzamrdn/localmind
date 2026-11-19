package com.localmind.app.core.termux

/**
 * Values published by Termux for the RUN_COMMAND intent contract.
 *
 * Keeping them here (instead of depending on `termux-shared`) keeps the app
 * free of an extra AAR while still matching the documented protocol:
 * https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 */
object TermuxConstants {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    /** Minimum Termux release that sends results through a PendingIntent. */
    const val MIN_RESULT_VERSION = "0.109"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** Runner name for a headless command (separate stdout and stderr). */
    const val RUNNER_APP_SHELL = "app-shell"

    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"

    /** Result extras returned by Termux in the "result" bundle. */
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    /** Activity.RESULT_OK, Termux's sentinel for "no internal error". */
    const val NO_INTERNAL_ERROR = -1

    /** Extra attached by LocalMind to route a result to the waiting caller. */
    const val EXTRA_EXECUTION_ID = "com.localmind.app.termux.EXECUTION_ID"

    /** Hard cap on a single command string handed to the intent. */
    const val MAX_COMMAND_CHARS = 4_000
}
