package com.localmind.app.feature.models

import com.localmind.app.core.i18n.UiKey
import com.localmind.app.core.i18n.t

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.domain.model.ModelValidation
import com.localmind.app.core.inference.EngineDiagnostics
import com.localmind.app.core.inference.LoadPhase
import com.localmind.app.core.inference.ModelRuntimeState
import com.localmind.app.core.inference.ResourceEstimate
import com.localmind.app.core.inference.ResourceRisk
import com.localmind.app.core.ui.components.ConfirmDialog
import com.localmind.app.core.ui.components.EmptyState
import com.localmind.app.core.ui.components.InfoBanner
import com.localmind.app.core.ui.components.MetaChip
import com.localmind.app.core.ui.components.ScreenHeader
import com.localmind.app.core.ui.components.ScreenSectionTitle
import com.localmind.app.core.ui.components.StatusPill
import com.localmind.app.core.ui.theme.LocalMindExtras
import com.localmind.app.core.util.formatFileSize

@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    modifier: Modifier = Modifier,
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val validatingIds by viewModel.validatingModelIds.collectAsStateWithLifecycle()
    val probingIds by viewModel.probingModelIds.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
    val loadRequest by viewModel.loadRequest.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var detailsModel by remember { mutableStateOf<LocalModel?>(null) }
    var configureModel by remember { mutableStateOf<LocalModel?>(null) }
    var removeTarget by remember { mutableStateOf<LocalModel?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importModels(uris) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.statusBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ScreenHeader(
                title = t(UiKey.navModels),
                subtitle = "Local GGUF library · ${viewModel.backendName}",
                actions = {
                    FilledTonalButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !isImporting,
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (isImporting) "Checking" else "Import")
                    }
                },
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "banner") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoBanner(
                            icon = Icons.Outlined.Info,
                            text = "Model files are opened through Android's document picker and are not copied. " +
                                "Imports validate the GGUF header and tensor layout only; runtime compatibility is " +
                                "checked when you load the model or run Test runtime compatibility. Removing a model " +
                                "never deletes the original file.",
                        )
                        if (viewModel.backendIsDemo) {
                            InfoBanner(
                                icon = Icons.Outlined.Info,
                                tone = MaterialTheme.colorScheme.tertiary,
                                text = "Native llama.cpp is not loadable on this device/build, so the chat uses the " +
                                    "demo engine. Files can still be imported and validated, but Load is disabled.",
                            )
                        }
                        if (models.isNotEmpty()) {
                            LibrarySummary(
                                models = models,
                                activeModelId = settings.activeModelId,
                                runtimeState = runtimeState,
                            )
                        }
                    }
                }

                if (models.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.Memory,
                            title = t(UiKey.modelsEmptyTitle),
                            body = "Import a .gguf file from device storage. LocalMind keeps only metadata and a " +
                                "persisted read permission.",
                            modifier = Modifier.padding(top = 40.dp),
                            content = {
                                FilledTonalButton(
                                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                                ) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Import GGUF")
                                }
                            },
                        )
                    }
                } else {
                    item(key = "section") {
                        ScreenSectionTitle(t(UiKey.modelsLibrary), modifier = Modifier.fillMaxWidth())
                    }
                    items(models, key = { it.id }) { model ->
                        val loaded = (runtimeState as? ModelRuntimeState.Loaded)?.modelId == model.id
                        val loading = (runtimeState as? ModelRuntimeState.Loading)
                            ?.takeIf { it.modelId == model.id }
                        val failure = (runtimeState as? ModelRuntimeState.Failed)
                            ?.takeIf { it.modelId == model.id }
                        ModelCard(
                            model = model,
                            isDefault = model.id == settings.activeModelId,
                            loaded = loaded,
                            loadingPhase = loading?.phase,
                            loadingPercent = loading?.percent ?: 0,
                            probing = model.id in probingIds,
                            validating = model.id in validatingIds,
                            failureMessage = failure?.message,
                            loadEnabled = !viewModel.backendIsDemo,
                            probeSupported = viewModel.backendSupportsProbe,
                            onLoad = { viewModel.requestLoad(model.id) },
                            onCancelLoad = viewModel::cancelLoad,
                            onUnload = viewModel::unload,
                            onSetDefault = { viewModel.setDefaultModel(model.id) },
                            onClearDefault = viewModel::clearDefaultModel,
                            onRevalidate = { viewModel.revalidate(model.id) },
                            onTestCompatibility = { viewModel.testCompatibility(model.id) },
                            onDiagnostics = { viewModel.openDiagnostics(model) },
                            onConfigure = { configureModel = model },
                            onDetails = { detailsModel = model },
                            onRemove = { removeTarget = model },
                            onThinkingModeChanged = { viewModel.onThinkingModeChanged(model.id, it) },
                        )
                    }
                    item(key = "milestone") {
                        MilestoneNote()
                    }
                }
            }
        }
    }

    detailsModel?.let { model ->
        ModelDetailsDialog(
            model = model,
            onRevalidate = {
                viewModel.revalidate(model.id)
                detailsModel = null
            },
            onDiagnostics = {
                viewModel.openDiagnostics(model)
                detailsModel = null
            },
            onDismiss = { detailsModel = null },
        )
    }

    diagnostics?.let { state ->
        DiagnosticsDialog(state = state, onDismiss = viewModel::closeDiagnostics)
    }

    configureModel?.let { model ->
        ConfigureModelDialog(
            model = model,
            maxThreads = viewModel.maxThreads,
            gpuSupported = viewModel.gpuSupported,
            onSave = { contextLength, threads, batch, gpu ->
                viewModel.configure(model.id, contextLength, threads, batch, gpu)
                configureModel = null
            },
            onDismiss = { configureModel = null },
        )
    }

    loadRequest?.let { request ->
        LoadConfirmationDialog(
            request = request,
            onConfirm = viewModel::confirmLoad,
            onDismiss = viewModel::dismissLoadRequest,
        )
    }

    removeTarget?.let { model ->
        ConfirmDialog(
            title = "Remove from library?",
            message = "\"${model.displayName}\" will be removed from LocalMind. The file stays in its original " +
                "location on your device.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { viewModel.removeModel(model.id) },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun LibrarySummary(
    models: List<LocalModel>,
    activeModelId: String?,
    runtimeState: ModelRuntimeState,
) {
    val extras = LocalMindExtras.current
    val active = models.firstOrNull { it.id == activeModelId }
    val loaded = runtimeState as? ModelRuntimeState.Loaded
    val totalBytes = models.sumOf { it.sizeBytes }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${models.size} model${if (models.size == 1) "" else "s"} · ${formatFileSize(totalBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = extras.mutedText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                loaded != null -> "Loaded: ${models.firstOrNull { it.id == loaded.modelId }?.displayName ?: "model"}"
                active != null -> "Selected: ${active.displayName}"
                else -> "No model selected"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (loaded != null) MaterialTheme.colorScheme.secondary else extras.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    isDefault: Boolean,
    loaded: Boolean,
    loadingPhase: LoadPhase?,
    loadingPercent: Int,
    probing: Boolean,
    validating: Boolean,
    failureMessage: String?,
    loadEnabled: Boolean,
    probeSupported: Boolean,
    onLoad: () -> Unit,
    onCancelLoad: () -> Unit,
    onUnload: () -> Unit,
    onSetDefault: () -> Unit,
    onClearDefault: () -> Unit,
    onRevalidate: () -> Unit,
    onTestCompatibility: () -> Unit,
    onDiagnostics: () -> Unit,
    onConfigure: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    onThinkingModeChanged: (Boolean) -> Unit,
) {
    val extras = LocalMindExtras.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = when (model.validation) {
                            ModelValidation.HEADER_VALID -> MaterialTheme.colorScheme.primary
                            ModelValidation.UNVERIFIED -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.horizontalScrollableRow(),
                    ) {
                        MetaChip(model.format.name)
                        model.quantizationLabel?.let { MetaChip(it) }
                    }
                    if (model.architecture != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = model.architecture,
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = if (model.isThinkingEnabled) "Thinking: ON" else "Thinking: OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (model.isThinkingEnabled) MaterialTheme.colorScheme.primary else extras.mutedText,
                    )
                    Spacer(Modifier.height(3.dp))
                    Switch(
                        checked = model.isThinkingEnabled,
                        onCheckedChange = {
                            onThinkingModeChanged(it)
                        },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "Model actions",
                            tint = extras.mutedText,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Set as selected") },
                            enabled = model.isUsable && !isDefault,
                            leadingIcon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                            onClick = { menuOpen = false; onSetDefault() },
                        )
                        if (isDefault) {
                            DropdownMenuItem(
                                text = { Text("Clear selection") },
                                onClick = { menuOpen = false; onClearDefault() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (probing) "Checking…" else "Test runtime") },
                            enabled = model.isUsable && !probing && probeSupported,
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            onClick = { menuOpen = false; onTestCompatibility() },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            enabled = probeSupported,
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = { menuOpen = false; onDiagnostics() },
                        )
                        DropdownMenuItem(
                            text = { Text(t(UiKey.modelsConfigure)) },
                            leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                            onClick = { menuOpen = false; onConfigure() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (validating) "Validating…" else "Re-validate") },
                            enabled = !validating,
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            onClick = { menuOpen = false; onRevalidate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Details") },
                            onClick = { menuOpen = false; onDetails() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp, bottom = 6.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when (model.validation) {
                    ModelValidation.HEADER_VALID -> StatusPill("Header validated", MaterialTheme.colorScheme.secondary)
                    ModelValidation.UNVERIFIED -> StatusPill("Unverified", MaterialTheme.colorScheme.tertiary)
                    ModelValidation.INVALID -> StatusPill("Invalid", MaterialTheme.colorScheme.error)
                    ModelValidation.MISSING -> StatusPill("Missing", MaterialTheme.colorScheme.error)
                }
                if (model.runtimeValidated) {
                    StatusPill("Runtime OK", MaterialTheme.colorScheme.secondary)
                } else if (model.runtimeMessage != null && model.validation == ModelValidation.HEADER_VALID) {
                    StatusPill("Runtime failed", MaterialTheme.colorScheme.error)
                }
                if (isDefault) {
                    StatusPill("Selected", MaterialTheme.colorScheme.primary)
                }
                if (loaded) {
                    StatusPill(t(UiKey.modelsLoaded), MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        loadingPhase != null || validating || probing -> "Working…"
                        loaded -> "In memory"
                        model.validation == ModelValidation.HEADER_VALID && !model.runtimeValidated -> "Runtime untested"
                        else -> t(UiKey.modelsNotLoaded)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }

            val validationMessage = model.validationMessage
            if (validationMessage != null) {
                Text(
                    text = validationMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (failureMessage != null) {
                Text(
                    text = failureMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else if (model.runtimeMessage != null && model.validation == ModelValidation.HEADER_VALID) {
                Text(
                    text = "Last runtime error: ${model.runtimeMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            if (loadingPhase != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { (loadingPercent.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${loadingPhase.label()} · $loadingPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.mutedText,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "${model.contextLength} ctx · " +
                        (if (model.threadCount == 0) "auto threads" else "${model.threadCount} threads") +
                        " · batch ${model.batchSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                    modifier = Modifier.weight(1f),
                )
                when {
                    loadingPhase != null -> OutlinedButton(onClick = onCancelLoad) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(t(UiKey.actionCancel))
                    }

                    loaded -> OutlinedButton(onClick = onUnload) { Text(t(UiKey.modelsUnload)) }

                    model.validation != ModelValidation.HEADER_VALID -> OutlinedButton(
                        onClick = onRevalidate,
                        enabled = !validating,
                    ) { Text(if (validating) "Checking…" else "Check file") }

                    else -> FilledTonalButton(
                        onClick = onLoad,
                        enabled = loadEnabled,
                    ) {
                        Text(if (loadEnabled) t(UiKey.modelsLoad) else "Backend off")
                    }
                }
            }
        }
    }
}

private fun LoadPhase.label(): String = when (this) {
    LoadPhase.PREPARING -> "Opening document"
    LoadPhase.COPYING -> "Copying to app cache"
    LoadPhase.PROBING -> "Checking runtime compatibility"
    LoadPhase.LOADING -> "Loading weights"
}

@Composable
private fun MilestoneNote() {
    InfoBanner(
        icon = Icons.Outlined.Info,
        tone = MaterialTheme.colorScheme.tertiary,
        text = "Load reads the GGUF weights in place when the document provider exposes a seekable file. " +
            "Providers that only offer a pipe are copied to the app cache for the duration of the load and the " +
            "copy is deleted on unload. Android may stop the app if free memory runs out, so large models show " +
            "a warning first.",
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun LoadConfirmationDialog(
    request: LoadRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val estimate = request.estimate
    val riskColor = when (estimate.risk) {
        ResourceRisk.COMFORTABLE -> MaterialTheme.colorScheme.secondary
        ResourceRisk.TIGHT -> LocalMindExtras.current.warning
        ResourceRisk.RISKY -> MaterialTheme.colorScheme.error
    }
    val riskLabel = when (estimate.risk) {
        ResourceRisk.COMFORTABLE -> "Comfortable"
        ResourceRisk.TIGHT -> "Tight fit"
        ResourceRisk.RISKY -> "Likely to fail"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load ${request.model.displayName}?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(riskLabel, riskColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = estimate.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalMindExtras.current.mutedText,
                    )
                }
                EstimateRow("Model weights", estimate.modelBytes)
                EstimateRow("KV cache", estimate.kvCacheBytes)
                EstimateRow("Compute buffers", estimate.computeOverheadBytes)
                EstimateRow("Estimated total", estimate.totalBytes, bold = true)
                Spacer(Modifier.height(2.dp))
                estimate.notes.forEach { note ->
                    Text(
                        text = "• $note",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (estimate.risk != ResourceRisk.COMFORTABLE) {
                    Text(
                        text = "Loading anyway may make Android kill the app or the backend may fail with an " +
                            "out-of-memory error. Unload other apps and prefer a smaller quantization/context.",
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (estimate.risk == ResourceRisk.RISKY) t(UiKey.modelsLoadAnyway) else t(UiKey.modelsLoad),
                    color = if (estimate.risk == ResourceRisk.RISKY) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(UiKey.actionCancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun EstimateRow(label: String, bytes: Long, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMindExtras.current.mutedText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatFileSize(bytes),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConfigureModelDialog(
    model: LocalModel,
    maxThreads: Int,
    gpuSupported: Boolean,
    onSave: (contextLength: Int, threadCount: Int, batchSize: Int, gpuLayers: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val trained = model.trainedContextLength?.takeIf { it > 0 }
    val contextOptions = remember(model.id, trained) {
        val base = listOf(1024, 2048, 4096, 8192, 16384, 32768)
        val capped = trained?.let { cap -> base.filter { it <= cap } } ?: base
        (capped + listOfNotNull(trained, model.contextLength))
            .distinct()
            .sorted()
            .ifEmpty { listOf(1024, 2048) }
    }
    var contextLength by remember(model.id) {
        mutableIntStateOf(model.contextLength.coerceAtLeast(512))
    }
    var threadCount by remember(model.id) { mutableIntStateOf(model.threadCount) }
    var batchSize by remember(model.id) { mutableIntStateOf(model.batchSize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inference configuration", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMindExtras.current.mutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Context length",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    contextOptions.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { option ->
                                FilterChip(
                                    selected = contextLength == option,
                                    onClick = { contextLength = option },
                                    label = { Text(if (option >= 1024) "${option / 1024}k" else "$option") },
                                )
                            }
                        }
                    }
                }
                if (trained != null) {
                    Text(
                        text = "Trained context: $trained tokens. Larger values are not offered.",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalMindExtras.current.mutedText,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = t(UiKey.settingsThreads),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.horizontalScrollableRow(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = threadCount == 0,
                        onClick = { threadCount = 0 },
                        label = { Text("Auto") },
                    )
                    (2..maxThreads.coerceAtMost(8)).forEach { value ->
                        FilterChip(
                            selected = threadCount == value,
                            onClick = { threadCount = value },
                            label = { Text("$value") },
                        )
                    }
                }
                Text(
                    text = "Auto leaves two cores for the UI and never uses more than six.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMindExtras.current.mutedText,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = t(UiKey.settingsBatch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(256, 512, 1024).forEach { value ->
                        FilterChip(
                            selected = batchSize == value,
                            onClick = { batchSize = value },
                            label = { Text("$value") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GPU acceleration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (gpuSupported) {
                                "Offload layers to a compatible GPU backend"
                            } else {
                                "Unavailable: this build uses a CPU-only llama.cpp backend"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalMindExtras.current.mutedText,
                        )
                    }
                    Switch(checked = false, onCheckedChange = null, enabled = false)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(contextLength, threadCount, batchSize, 0)
            }) { Text(t(UiKey.actionSave), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(UiKey.actionCancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun ModelDetailsDialog(
    model: LocalModel,
    onRevalidate: () -> Unit,
    onDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailRow("Header check", model.validation.name.lowercase())
                DetailRow(
                    "Runtime status",
                    when {
                        model.runtimeValidated -> "compatible with this build"
                        model.runtimeMessage != null -> t(UiKey.chatToolFailed)
                        else -> "not tested yet"
                    },
                )
                model.runtimeDescription?.let { DetailRow("Runtime detail", it) }
                DetailRow("Architecture", model.architecture ?: "not reported")
                DetailRow("Parameters", model.parameterCount?.let { formatParameterCount(it) } ?: "unknown")
                DetailRow("Tensor quantization", model.quantizationLabel ?: "not detected")
                DetailRow("Trained context", model.trainedContextLength?.let { "$it tokens" } ?: "unknown")
                DetailRow("Layers", model.blockCount?.toString() ?: "unknown")
                DetailRow("Embedding", model.embeddingLength?.toString() ?: "unknown")
                DetailRow("Attention heads", model.headCount?.toString() ?: "unknown")
                DetailRow("KV heads", model.headCountKv?.toString() ?: "unknown")
                DetailRow("Chat template", if (model.chatTemplate != null) "embedded" else "fallback formatting")
                DetailRow("Size", if (model.sizeBytes > 0) formatFileSize(model.sizeBytes) else "unknown")
                DetailRow("Load config", "${model.contextLength} ctx · " +
                    (if (model.threadCount == 0) "auto threads" else "${model.threadCount} threads") +
                    " · batch ${model.batchSize}")
                model.validationMessage?.let { message ->
                    DetailRow("Issue", message)
                }
                model.runtimeMessage?.let { message ->
                    DetailRow("Runtime error", message)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Document URI",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMindExtras.current.mutedText,
                )
                Text(
                    text = model.uri,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDiagnostics) { Text("Diagnostics") }
                TextButton(onClick = onRevalidate) { Text("Re-validate") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(UiKey.actionClose)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun DiagnosticsDialog(
    state: DiagnosticsState,
    onDismiss: () -> Unit,
) {
    val extras = LocalMindExtras.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = state.model.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.mutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val data = state.diagnostics
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Collecting backend state…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (data == null) {
                    Text(
                        text = "The native backend did not return diagnostics for this model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    DetailRow("Architecture", data.architecture ?: "not reported")
                    DetailRow("Quantization", data.quantization ?: "not detected")
                    DetailRow("Load config", data.loadConfig)
                    Spacer(Modifier.height(4.dp))
                    DiagnosticBlock("File accessibility", data.fileAccess)
                    DiagnosticBlock("Backend", data.backendInfo)
                    data.lastError?.let { DiagnosticBlock("Last error", it, error = true) }
                    data.nativeLog?.let { DiagnosticBlock("Native log (llama.cpp)", it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(UiKey.actionClose)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun DiagnosticBlock(label: String, value: String, error: Boolean = false) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = LocalMindExtras.current.mutedText,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                MaterialTheme.shapes.small,
            )
            .padding(8.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMindExtras.current.mutedText,
            modifier = Modifier.width(128.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Modifier.horizontalScrollableRow(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

private fun formatParameterCount(count: Long): String = when {
    count >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1fB", count / 1_000_000_000.0)
    count >= 1_000_000L -> String.format(java.util.Locale.US, "%.0fM", count / 1_000_000.0)
    else -> count.toString()
}
