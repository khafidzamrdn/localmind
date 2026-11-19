package com.localmind.app.feature.termux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.app.core.termux.TermuxBridge
import com.localmind.app.core.termux.TermuxRunResult
import com.localmind.app.core.termux.TermuxStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manual console for the Termux bridge.
 *
 * This screen exists so the bridge can be tested without a model in the loop.
 * It never runs anything on its own: the only trigger is the Run button.
 */
class TermuxConsoleViewModel(
    private val bridge: TermuxBridge,
) : ViewModel() {

    private val _command = MutableStateFlow("")
    val command: StateFlow<String> = _command.asStateFlow()

    private val _timeoutSeconds = MutableStateFlow(DEFAULT_TIMEOUT_SECONDS)
    val timeoutSeconds: StateFlow<Int> = _timeoutSeconds.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _result = MutableStateFlow<TermuxRunResult?>(null)
    val result: StateFlow<TermuxRunResult?> = _result.asStateFlow()

    private val _status = MutableStateFlow(bridge.status())
    val status: StateFlow<TermuxStatus> = _status.asStateFlow()

    fun onCommandChange(value: String) {
        _command.value = value
    }

    fun setTimeoutSeconds(seconds: Int) {
        _timeoutSeconds.value = seconds
    }

    fun refreshStatus() {
        _status.value = bridge.status()
    }

    fun clearOutput() {
        _result.value = null
    }

    fun run() {
        val command = _command.value.trim()
        if (command.isEmpty() || _running.value) return
        refreshStatus()
        viewModelScope.launch {
            _running.value = true
            _result.value = null
            _result.value = bridge.run(
                command = command,
                timeoutMs = _timeoutSeconds.value * 1_000L,
                label = "LocalMind console",
            )
            _running.value = false
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30
    }
}
