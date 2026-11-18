package com.localmind.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localmind.app.core.domain.model.AppSettings
import com.localmind.app.core.domain.model.ThemeMode
import com.localmind.app.core.ui.components.ConfirmDialog
import com.localmind.app.core.ui.components.InfoBanner
import com.localmind.app.core.ui.components.ScreenHeader
import com.localmind.app.core.ui.components.ScreenSectionTitle
import com.localmind.app.core.ui.components.StatusPill
import com.localmind.app.core.ui.theme.LocalMindExtras
import com.localmind.app.core.util.formatFileSize
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val deviceMemory by viewModel.deviceMemory.collectAsStateWithLifecycle()

    var promptDraft by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var confirmClearChats by remember { mutableStateOf(false) }

    val activeModel = models.firstOrNull { it.id == settings.activeModelId }
    val extras = LocalMindExtras.current

    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = "Settings",
                subtitle = "Local defaults, generation and privacy",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
        }

        item(key = "appearance-title") { ScreenSectionTitle("Appearance") }
        item(key = "appearance") {
            SettingsCard {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.SYSTEM -> "System"
                        }
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        item(key = "model-title") { ScreenSectionTitle("Model") }
        item(key = "model") {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeModel?.displayName ?: "Demo engine",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (activeModel != null) {
                                if (viewModel.backendIsDemo) {
                                    "Selected · native backend unavailable in this build"
                                } else {
                                    "Selected · loads on demand when you send a message"
                                }
                            } else {
                                if (viewModel.backendIsDemo) {
                                    "Built-in simulated backend"
                                } else {
                                    "No model selected · import a GGUF file"
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                        )
                    }
                    TextButton(onClick = onOpenModels) { Text("Manage") }
                }
                if (activeModel != null && activeModel.quantization != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Quantization ${activeModel.quantization} · ${formatFileSize(activeModel.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
            }
        }

        item(key = "chat-title") { ScreenSectionTitle("Chat") }
        item(key = "chat") {
            SettingsCard {
                Text(
                    text = "System prompt",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 9,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            promptDraft = AppSettings.DEFAULT_SYSTEM_PROMPT
                            viewModel.resetSystemPrompt()
                        },
                    ) { Text("Reset") }
                    TextButton(
                        onClick = { viewModel.setSystemPrompt(promptDraft) },
                        enabled = promptDraft.isNotBlank() && promptDraft != settings.systemPrompt,
                    ) { Text("Save") }
                }
            }
        }

        item(key = "generation-title") { ScreenSectionTitle("Generation") }
        item(key = "generation") {
            SettingsCard {
                SettingsSlider(
                    label = "Temperature",
                    value = settings.generation.temperature.toFloat(),
                    range = 0f..1.5f,
                    steps = 29,
                    format = { String.format(Locale.US, "%.2f", it) },
                    onValueChangeFinished = { viewModel.setTemperature(it.toDouble()) },
                )
                SettingsSlider(
                    label = "Top-p",
                    value = settings.generation.topP.toFloat(),
                    range = 0.1f..1f,
                    steps = 17,
                    format = { String.format(Locale.US, "%.2f", it) },
                    onValueChangeFinished = { viewModel.setTopP(it.toDouble()) },
                )
                SettingsSlider(
                    label = "Top-k",
                    value = settings.generation.topK.toFloat(),
                    range = 1f..100f,
                    steps = 98,
                    format = { it.toInt().toString() },
                    onValueChangeFinished = { viewModel.setTopK(it.toInt()) },
                )
                SettingsSlider(
                    label = "Repeat penalty",
                    value = settings.generation.repeatPenalty.toFloat(),
                    range = 1f..1.5f,
                    steps = 9,
                    format = { String.format(Locale.US, "%.2f", it) },
                    onValueChangeFinished = { viewModel.setRepeatPenalty(it.toDouble()) },
                )
                SettingsSlider(
                    label = "Max tokens",
                    value = settings.generation.maxTokens.toFloat(),
                    range = 128f..4096f,
                    steps = 30,
                    format = { it.toInt().toString() },
                    onValueChangeFinished = { viewModel.setMaxTokens(it.toInt()) },
                )
            }
        }

        item(key = "context-title") { ScreenSectionTitle("Context window") }
        item(key = "context") {
            SettingsCard {
                Text(
                    text = "Context length",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(2048, 4096, 8192, 16384, 32768).forEach { length ->
                        FilterChip(
                            selected = settings.generation.contextLength == length,
                            onClick = { viewModel.setContextLength(length) },
                            label = { Text("${length / 1024}k") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This is the app-wide default. A selected model can override it in Models → Configure; " +
                        "the backend caps it to the context length the model was trained for.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        item(key = "runtime-title") { ScreenSectionTitle("Runtime") }
        item(key = "runtime") {
            SettingsCard {
                BackendRow(
                    "CPU cores",
                    "${viewModel.capabilities.cpuCores} available",
                    MaterialTheme.colorScheme.onSurface,
                )
                BackendRow(
                    "Device memory",
                    "${formatFileSize(deviceMemory.totalBytes)} total · " +
                        "${formatFileSize(deviceMemory.availableBytes)} free",
                    MaterialTheme.colorScheme.onSurface,
                )
                if (deviceMemory.lowRamDevice) {
                    Spacer(Modifier.height(4.dp))
                    InfoBanner(
                        icon = Icons.Outlined.Info,
                        tone = extras.warning,
                        text = "Android reports this as a low-RAM device. Prefer small quantized models " +
                            "(Q4) and a 2k–4k context.",
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Default generation threads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.runtime.threadCount == 0,
                        onClick = { viewModel.setThreadCount(0) },
                        label = { Text("Auto (${viewModel.capabilities.maxThreads.coerceIn(2, 6)})") },
                    )
                    (2..viewModel.capabilities.maxThreads.coerceAtMost(8)).forEach { threads ->
                        FilterChip(
                            selected = settings.runtime.threadCount == threads,
                            onClick = { viewModel.setThreadCount(threads) },
                            label = { Text("$threads") },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Default batch size",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(256, 512, 1024).forEach { batch ->
                        FilterChip(
                            selected = settings.runtime.batchSize == batch,
                            onClick = { viewModel.setBatchSize(batch) },
                            label = { Text("$batch") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Threads apply to newly imported models; existing models can be tuned in Models → " +
                        "Configure. Leave two cores free so the UI stays responsive.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        item(key = "agent-title") { ScreenSectionTitle("Agent & tool policy") }
        item(key = "agent") {
            SettingsCard {
                PolicyRow(
                    icon = Icons.Outlined.Visibility,
                    title = "Read workspace files",
                    summary = "Allowed inside an explicitly granted folder",
                    state = "read-only",
                    tone = MaterialTheme.colorScheme.secondary,
                )
                PolicyRow(
                    icon = Icons.Outlined.Edit,
                    title = "Write or edit files",
                    summary = "Requires diff preview and approval",
                    state = "approval",
                    tone = extras.warning,
                )
                PolicyRow(
                    icon = Icons.Outlined.Delete,
                    title = "Delete files",
                    summary = "Requires a dedicated confirmation",
                    state = "confirm",
                    tone = extras.warning,
                )
                PolicyRow(
                    icon = Icons.Outlined.PlayArrow,
                    title = "Execute commands or tests",
                    summary = "Only when a matching runtime is present",
                    state = "approval",
                    tone = extras.warning,
                )
                PolicyRow(
                    icon = Icons.Outlined.Public,
                    title = "Network access",
                    summary = "No INTERNET permission in this build",
                    state = "off",
                    tone = extras.mutedText,
                )
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    icon = Icons.Outlined.Lock,
                    text = "The policy is listed here for transparency. Tool execution and its enforcement ship in " +
                        "Milestone 3; no tool can run in this build.",
                )
            }
        }

        item(key = "storage-title") { ScreenSectionTitle("Storage") }
        item(key = "storage") {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chat database",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Conversations and messages · ${formatFileSize(storageBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                        )
                    }
                    TextButton(
                        onClick = { confirmClearChats = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Delete all chats") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Imported model files live outside the app and are never deleted from here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        item(key = "backend-title") { ScreenSectionTitle("Backend") }
        item(key = "backend") {
            SettingsCard {
                BackendRow(
                    "Active engine",
                    viewModel.backendName,
                    if (viewModel.backendIsDemo) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
                BackendRow(
                    "Native GGUF backend",
                    if (viewModel.backendIsDemo) "Unavailable" else "llama.cpp · CPU",
                    if (viewModel.backendIsDemo) extras.mutedText else MaterialTheme.colorScheme.secondary,
                )
                BackendRow(
                    "GPU acceleration",
                    if (viewModel.gpuSupported) "Supported" else "Not available (CPU build)",
                    if (viewModel.gpuSupported) MaterialTheme.colorScheme.secondary else extras.mutedText,
                )
                BackendRow("Network", "Offline · no INTERNET permission", MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (viewModel.backendIsDemo) {
                        "The native library could not be loaded on this device/build, so LocalMind falls back " +
                            "to the simulated engine. Imported files are never read by the demo engine."
                    } else {
                        "LocalMind bundles llama.cpp built for arm64 Android. Inference runs on the CPU with " +
                            "the context, thread and batch settings above; no data leaves the device."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        item(key = "about-title") { ScreenSectionTitle("About") }
        item(key = "about") {
            SettingsCard {
                AboutRow("Version", "0.2.0 · Milestone 2")
                AboutRow("Package", "com.localmind.app")
                AboutRow("Minimum Android", "10 (API 29)")
                AboutRow("Data", "Stored on device only")
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "LocalMind is an offline-first local AI workspace. No analytics and no telemetry.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        item(key = "footer") {
            Text(
                text = "Demo responses are clearly labeled. Nothing is sent to a server.",
                style = MaterialTheme.typography.labelSmall,
                color = extras.mutedText,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }

    if (confirmClearChats) {
        ConfirmDialog(
            title = "Delete all conversations?",
            message = "Every stored conversation and message will be permanently removed from this device. " +
                "Imported model files are not affected.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = { viewModel.clearAllChats() },
            onDismiss = { confirmClearChats = false },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val extras = LocalMindExtras.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onValueChangeFinished: (Float) -> Unit,
) {
    var localValue by remember(value, range) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(localValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = localValue,
            onValueChange = { localValue = it },
            onValueChangeFinished = { onValueChangeFinished(localValue) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun PolicyRow(
    icon: ImageVector,
    title: String,
    summary: String,
    state: String,
    tone: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalMindExtras.current.mutedText,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = LocalMindExtras.current.mutedText,
            )
        }
        StatusPill(state, tone)
    }
}

@Composable
private fun BackendRow(label: String, value: String, tone: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMindExtras.current.mutedText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMindExtras.current.mutedText,
            modifier = Modifier.width(130.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
