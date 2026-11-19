package com.localmind.app.core.termux

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges Termux's fire-and-forget result intent back to the coroutine waiting
 * for it. Entries are keyed by the unique request code used for the pending
 * intent, so concurrent runs cannot confuse their outputs.
 *
 * The registry is intentionally in-memory: if Android kills LocalMind while a
 * command runs, the result is discarded rather than shown out of context.
 */
internal object TermuxResultDispatcher {

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxRawResult>>()

    fun register(executionId: Int): CompletableDeferred<TermuxRawResult> {
        val deferred = CompletableDeferred<TermuxRawResult>()
        pending[executionId] = deferred
        return deferred
    }

    fun forget(executionId: Int) {
        pending.remove(executionId)
    }

    fun deliver(executionId: Int, result: TermuxRawResult) {
        pending.remove(executionId)?.complete(result)
    }
}
