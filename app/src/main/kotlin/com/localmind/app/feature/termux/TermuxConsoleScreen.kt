package com.localmind.app.feature.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localmind.app.core.termux.TermuxConstants
import com.localmind.app.core.termux.TermuxRunResult
import com.localmind.app.core.ui.components.InfoBanner
import com.localmind.app.core.ui.components.ScreenSectionTitle
import com.localmind.app.core.ui.components.StatusPill
import com.localmind.app.core.ui.theme.LocalMindExtras
import java.util.Locale

/**
 * Manual test console for the Termux bridge. The AI never drives this screen;
 * it is the safe place to verify the permission and allow-external-apps setup.
 */
@Composable
fun TermuxConsoleScreen(
    viewModel: TermuxConsoleViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val command by viewModel.command.collectAsStateWithLifecycle()
    val timeoutSeconds by viewModel.timeoutSeconds.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val extras = LocalMindExtras.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to settings",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Termux console",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Manual bridge test · nothing runs automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.mutedText,
                    )
                }
            }
        }

        item(key = "status-title") { ScreenSectionTitle("Bridge status") }
        item(key = "status") {
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Termux RUN_COMMAND",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = status.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        text = status.headline,
                        color = when {
                            status.ready -> MaterialTheme.colorScheme.secondary
                            status.installed -> extras.warning
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatusRow("Installed", if (status.installed) "Yes" else "No", status.installed)
                StatusRow(
                    label = "Version",
                    value = status.versionName ?: "—",
                    ok = status.versionSupportsResults,
                )
                StatusRow(
                    label = "RUN_COMMAND permission",
                    value = if (status.permissionGranted) "Granted" else "Not granted",
                    ok = status.permissionGranted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openAppInfo(context, TermuxConstants.TERMUX_PACKAGE) }) {
                        Text("Termux app info")
                    }
                    TextButton(onClick = { openAppInfo(context, context.packageName) }) {
                        Text("LocalMind permissions")
                    }
                    TextButton(onClick = viewModel::refreshStatus) { Text("Refresh") }
                }
            }
        }

        if (!status.ready) {
            item(key = "setup-title") { ScreenSectionTitle("Setup") }
            item(key = "setup") {
                ConsoleCard {
                    Text(
                        text = "To let LocalMind hand commands to Termux:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    SetupStep(1, "Install Termux from F-Droid (package com.termux).", status.installed)
                    SetupStep(
                        2,
                        "Grant LocalMind “Run commands in Termux”: Android Settings → Apps → LocalMind → " +
                            "Permissions → Additional permissions.",
                        status.permissionGranted,
                    )
                    SetupStep(3, "Inside Termux, allow external apps: run the command below, then restart Termux.", null)
                    Spacer(Modifier.height(4.dp))
                    TerminalOutput(
                        text = "echo 'allow-external-apps=true' >> ~/.termux/termux.properties",
                        tone = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Termux 0.109 or newer is required so LocalMind can read stdout, stderr and the " +
                            "exit code. Granting draw-over-apps and disabling battery optimisation for Termux " +
                            "helps on some devices.",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
            }
        }

        item(key = "console-title") { ScreenSectionTitle("Run a command") }
        item(key = "console") {
            ConsoleCard {
                OutlinedTextField(
                    value = command,
                    onValueChange = viewModel::onCommandChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("Command (runs with bash -c from ~)") },
                    placeholder = { Text("ls -la ~") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Wait limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10, 30, 60, 120).forEach { seconds ->
                        FilterChip(
                            selected = timeoutSeconds == seconds,
                            onClick = { viewModel.setTimeoutSeconds(seconds) },
                            label = { Text("${seconds}s") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = viewModel::run,
                        enabled = command.isNotBlank() && !running && status.ready,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Running…" else "Run command")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (result != null) {
                        TextButton(onClick = viewModel::clearOutput) { Text("Clear output") }
                    }
                }
                if (!status.ready) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Finish the setup above before running a command.",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.warning,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The command runs under Termux's user, not LocalMind's. LocalMind only receives the " +
                        "captured output. A wait limit does not kill the command inside Termux.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        result?.let { output ->
            item(key = "result-title") { ScreenSectionTitle("Result") }
            item(key = "result") {
                ResultCard(output = output)
            }
        }

        item(key = "footer") {
            InfoBanner(
                icon = Icons.Outlined.Info,
                text = "This is the same bridge the chat tool uses. In chat, every model-proposed command " +
                    "must be approved with the Run button before it is sent to Termux.",
                tone = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun ResultCard(output: TermuxRunResult) {
    val extras = LocalMindExtras.current
    val clipboard = LocalClipboardManager.current
    val copyText = when (output) {
        is TermuxRunResult.Completed -> buildString {
            append(output.stdout)
            if (output.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(output.stderr)
            }
        }
        is TermuxRunResult.Failed -> listOfNotNull(output.message, output.hint).joinToString("\n")
        is TermuxRunResult.TimedOut -> listOfNotNull(
            "Timed out after ${output.timeoutMs / 1000}s",
            output.hint,
        ).joinToString("\n")
    }

    ConsoleCard {
        when (output) {
            is TermuxRunResult.Completed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = "exit ${output.exitCode}",
                        color = if (output.isSuccess) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = String.format(Locale.US, "%.1f s", output.durationMs / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(copyText)) },
                        enabled = copyText.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
                if (output.stdout.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OutputSection("stdout", output.stdout, output.stdoutTruncated)
                }
                if (output.stderr.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OutputSection(
                        label = "stderr",
                        text = output.stderr,
                        truncated = output.stderrTruncated,
                        tone = MaterialTheme.colorScheme.error,
                    )
                }
                if (output.stdout.isBlank() && output.stderr.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The command finished without output.",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
            }

            is TermuxRunResult.Failed -> {
                StatusPill("failed", MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = output.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                output.hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.warning,
                    )
                }
            }

            is TermuxRunResult.TimedOut -> {
                StatusPill("timeout", extras.warning)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "LocalMind stopped waiting after ${output.timeoutMs / 1000} seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                output.hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputSection(
    label: String,
    text: String,
    truncated: Boolean,
    tone: Color = MaterialTheme.colorScheme.onSurface,
) {
    val extras = LocalMindExtras.current
    val display = if (text.length > MAX_DISPLAY_CHARS) text.take(MAX_DISPLAY_CHARS) else text
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = extras.mutedText,
    )
    Spacer(Modifier.height(2.dp))
    TerminalOutput(text = display, tone = tone)
    if (truncated || display.length < text.length) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Output truncated for display.",
            style = MaterialTheme.typography.labelSmall,
            color = extras.mutedText,
        )
    }
}

@Composable
private fun TerminalOutput(text: String, tone: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = tone,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    val extras = LocalMindExtras.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = extras.mutedText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) MaterialTheme.colorScheme.secondary else extras.warning,
        )
    }
}

@Composable
private fun SetupStep(index: Int, text: String, done: Boolean?) {
    val extras = LocalMindExtras.current
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodySmall,
            color = if (done == true) MaterialTheme.colorScheme.secondary else extras.mutedText,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = text + if (done == true) "  ✓" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (done == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConsoleCard(content: @Composable ColumnScope.() -> Unit) {
    val extras = LocalMindExtras.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

private fun openAppInfo(context: Context, packageName: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val MAX_DISPLAY_CHARS = 12_000
