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
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.localmind.app.core.i18n.AppLanguage
import com.localmind.app.core.i18n.UiKey
import com.localmind.app.core.i18n.t
import com.localmind.app.core.tool.ToolRegistry
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
    onOpenTermux: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val deviceMemory by viewModel.deviceMemory.collectAsStateWithLifecycle()
    val termuxStatus by viewModel.termuxStatus.collectAsStateWithLifecycle()

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
                title = t(UiKey.settingsTitle),
                subtitle = t(UiKey.settingsSubtitle),
                modifier = Modifier.padding(horizontal = 0.dp),
            )
        }

        item(key = "appearance-title") { ScreenSectionTitle(t(UiKey.settingsAppearance)) }
        item(key = "appearance") {
            SettingsCard {
                Text(
                    text = t(UiKey.settingsTheme),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ThemeMode.DARK -> t(UiKey.settingsThemeDark)
                            ThemeMode.LIGHT -> t(UiKey.settingsThemeLight)
                            ThemeMode.SYSTEM -> t(UiKey.settingsThemeSystem)
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

        item(key = "language-title") { ScreenSectionTitle(t(UiKey.settingsLanguage)) }
        item(key = "language") {
            SettingsCard {
                Text(
                    text = t(UiKey.settingsLanguageSummary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = settings.language == language,
                            onClick = { viewModel.setLanguage(language) },
                            label = { Text(language.nativeName) },
                        )
                    }
                }
            }
        }

        item(key = "model-title") { ScreenSectionTitle(t(UiKey.settingsModel)) }
        item(key = "model") {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeModel?.displayName ?: t(UiKey.chatDemoEngine),
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

        item(key = "chat-title") { ScreenSectionTitle(t(UiKey.navChat)) }
        item(key = "chat") {
            SettingsCard {
                Text(
                    text = t(UiKey.settingsSystemPrompt),
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
                    ) { Text(t(UiKey.actionSave)) }
                }
            }
        }

        item(key = "generation-title") { ScreenSectionTitle(t(UiKey.settingsGeneration)) }
        item(key = "generation") {
            SettingsCard {
                SettingsSlider(
                    label = t(UiKey.settingsTemperature),
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
                    label = t(UiKey.settingsRepeatPenalty),
                    value = settings.generation.repeatPenalty.toFloat(),
                    range = 1f..1.5f,
                    steps = 9,
                    format = { String.format(Locale.US, "%.2f", it) },
                    onValueChangeFinished = { viewModel.setRepeatPenalty(it.toDouble()) },
                )
                SettingsSlider(
                    label = t(UiKey.settingsMaxTokens),
                    value = settings.generation.maxTokens.toFloat(),
                    range = 128f..4096f,
                    steps = 30,
                    format = { it.toInt().toString() },
                    onValueChangeFinished = { viewModel.setMaxTokens(it.toInt()) },
                )
            }
        }

        item(key = "context-title") { ScreenSectionTitle(t(UiKey.settingsContext)) }
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

        item(key = "runtime-title") { ScreenSectionTitle(t(UiKey.settingsRuntime)) }
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
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t(UiKey.settingsLowMemory),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (settings.runtime.lowMemory) {
                                t(UiKey.settingsLowMemoryOn)
                            } else {
                                t(UiKey.settingsLowMemoryOff)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                        )
                    }
                    Switch(
                        checked = settings.runtime.lowMemory,
                        onCheckedChange = viewModel::setLowMemory,
                    )
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

        item(key = "agent-title") { ScreenSectionTitle(t(UiKey.settingsAgent)) }
        item(key = "agent") {
            SettingsCard {
                PolicyRow(
                    icon = Icons.Outlined.Visibility,
                    title = t(UiKey.settingsReadWorkspace),
                    summary = t(UiKey.settingsReadWorkspaceSummary),
                    state = t(UiKey.settingsStateReadOnly),
                    tone = MaterialTheme.colorScheme.secondary,
                )
                PolicyRow(
                    icon = Icons.Outlined.Edit,
                    title = t(UiKey.settingsWriteWorkspace),
                    summary = t(UiKey.settingsWriteWorkspaceSummary),
                    state = t(UiKey.chatToolApproval),
                    tone = extras.warning,
                )
                PolicyRow(
                    icon = Icons.Outlined.Delete,
                    title = t(UiKey.settingsDeleteFiles),
                    summary = t(UiKey.settingsDeleteFilesSummary),
                    state = "confirm",
                    tone = extras.warning,
                )
                PolicyRow(
                    icon = Icons.Outlined.PlayArrow,
                    title = t(UiKey.settingsExecute),
                    summary = if (settings.agentAutoReadOnly) {
                        t(UiKey.settingsExecuteSmart)
                    } else {
                        t(UiKey.settingsExecuteApproval)
                    },
                    state = if (settings.agentAutoReadOnly) t(UiKey.settingsStateSmart) else t(UiKey.chatToolApproval),
                    tone = extras.warning,
                )
                PolicyRow(
                    icon = Icons.Outlined.Public,
                    title = t(UiKey.settingsNetwork),
                    summary = t(UiKey.settingsNetworkSummary),
                    state = t(UiKey.settingsStateOff),
                    tone = extras.mutedText,
                )

                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                                    text = t(UiKey.settingsTermux),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = ToolRegistry.TERMUX_RUN_SUMMARY,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = extras.mutedText,
                                )
                            }
                            Switch(
                                checked = settings.toolsEnabled,
                                enabled = termuxStatus.ready || settings.toolsEnabled,
                                onCheckedChange = viewModel::setToolsEnabled,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 26.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t(UiKey.settingsAutoReadOnly),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (settings.agentAutoReadOnly) {
                                        t(UiKey.settingsAutoReadOnlyOn)
                                    } else {
                                        t(UiKey.settingsAutoReadOnlyOff)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = extras.mutedText,
                                )
                            }
                            Switch(
                                checked = settings.agentAutoReadOnly,
                                enabled = settings.toolsEnabled,
                                onCheckedChange = viewModel::setAgentAutoReadOnly,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        BackendRow(
                            t(UiKey.settingsTermuxStatus),
                            termuxStatus.headline,
                            when {
                                termuxStatus.ready -> MaterialTheme.colorScheme.secondary
                                termuxStatus.installed -> extras.warning
                                else -> extras.mutedText
                            },
                        )
                        BackendRow(
                            t(UiKey.settingsVersion),
                            termuxStatus.versionName ?: "—",
                            if (termuxStatus.versionSupportsResults) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                extras.warning
                            },
                        )
                        BackendRow(
                            t(UiKey.settingsTermuxPermission),
                            if (termuxStatus.permissionGranted) t(UiKey.commonGranted) else t(UiKey.commonNotGranted),
                            if (termuxStatus.permissionGranted) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                extras.warning
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onOpenTermux) { Text(t(UiKey.settingsOpenTermux)) }
                        }
                        if (!termuxStatus.ready) {
                            Spacer(Modifier.height(6.dp))
                            InfoBanner(
                                icon = Icons.Outlined.Terminal,
                                tone = extras.warning,
                                text = termuxStatus.summary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (settings.toolsEnabled) {
                        if (settings.agentAutoReadOnly) {
                            "On. The model plans and runs up to 6 steps per request. Read-only commands run " +
                                "automatically; anything that writes, installs, deletes or uses the network " +
                                "still waits for your approval. Blocked commands never run."
                        } else {
                            "On. The model may propose one Termux command per reply. Every command waits " +
                                "for your approval, and its output is added as a tool result."
                        }
                    } else {
                        "Off. The model is not told about any tool. Turn this on to let it plan Termux " +
                            "commands; approval rules still apply."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    icon = Icons.Outlined.Lock,
                    text = "File read/write, delete and network tools are not implemented yet. The policy above " +
                        "describes the intended rules; only the Termux command bridge is active in this build.",
                )
            }
        }

        item(key = "storage-title") { ScreenSectionTitle(t(UiKey.settingsStorage)) }
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
                    ) { Text(t(UiKey.settingsDeleteChats)) }
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

        item(key = "about-title") { ScreenSectionTitle(t(UiKey.settingsAbout)) }
        item(key = "about") {
            SettingsCard {
                AboutRow(t(UiKey.settingsVersion), "0.4.0 · Agent loop")
                AboutRow(t(UiKey.settingsPackage), "com.localmind.app")
                AboutRow(t(UiKey.settingsMinAndroid), "10 (API 29)")
                AboutRow(t(UiKey.settingsData), t(UiKey.settingsDataValue))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = t(UiKey.settingsAboutBody),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }

        item(key = "footer") {
            Text(
                text = t(UiKey.settingsFooter),
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
