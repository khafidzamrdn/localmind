package com.localmind.app.core.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the result bundle Termux sends through the per-execution pending
 * intent. The receiver is not exported; the pending intent is created by this
 * app, so Termux delivers it with LocalMind's own identity.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val executionId = intent.getIntExtra(TermuxConstants.EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) return
        val bundle = intent.getBundleExtra(TermuxConstants.RESULT_BUNDLE) ?: return

        val result = TermuxRawResult(
            stdout = bundle.getString(TermuxConstants.RESULT_STDOUT).orEmpty(),
            stderr = bundle.getString(TermuxConstants.RESULT_STDERR).orEmpty(),
            exitCode = if (bundle.containsKey(TermuxConstants.RESULT_EXIT_CODE)) {
                bundle.getInt(TermuxConstants.RESULT_EXIT_CODE)
            } else {
                null
            },
            errorCode = bundle.getInt(TermuxConstants.RESULT_ERR, TermuxConstants.NO_INTERNAL_ERROR),
            errorMessage = bundle.getString(TermuxConstants.RESULT_ERRMSG),
            stdoutOriginalLength = null,
            stderrOriginalLength = null,
        )
        TermuxResultDispatcher.deliver(executionId, result)
    }
}
