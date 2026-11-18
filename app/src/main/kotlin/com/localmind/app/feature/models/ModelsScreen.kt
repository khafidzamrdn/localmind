package com.localmind.app.feature.models

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.localmind.app.core.domain.model.ModelFormat
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
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var detailsModel by remember { mutableStateOf<LocalModel?>(null) }
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
                title = "Models",
                subtitle = "Local GGUF library",
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
                        Text(if (isImporting) "Importing" else "Import")
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
                                "Removing a model from this library never deletes the original file.",
                        )
                        if (models.isNotEmpty()) {
                            LibrarySummary(
                                models = models,
                                activeModelId = settings.activeModelId,
                            )
                        }
                    }
                }

                if (models.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.Memory,
                            title = "No models yet",
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
                        ScreenSectionTitle("Library", modifier = Modifier.fillMaxWidth())
                    }
                    items(models, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            isDefault = model.id == settings.activeModelId,
                            onSetDefault = { viewModel.setDefaultModel(model.id) },
                            onClearDefault = viewModel::clearDefaultModel,
                            onDetails = { detailsModel = model },
                            onRemove = { removeTarget = model },
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
        ModelDetailsDialog(model = model, onDismiss = { detailsModel = null })
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
private fun LibrarySummary(models: List<LocalModel>, activeModelId: String?) {
    val extras = LocalMindExtras.current
    val active = models.firstOrNull { it.id == activeModelId }
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
            text = active?.displayName?.let { "Default: $it" } ?: "Default: demo engine",
            style = MaterialTheme.typography.labelSmall,
            color = if (active != null) MaterialTheme.colorScheme.primary else extras.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onClearDefault: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    val extras = LocalMindExtras.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MetaChip(model.format.name)
                        model.quantization?.let { MetaChip(it) }
                        MetaChip(if (model.sizeBytes > 0) formatFileSize(model.sizeBytes) else "size unknown")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "Model actions",
                            tint = extras.mutedText,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Set as default") },
                            enabled = model.isUsable && !isDefault,
                            leadingIcon = { Icon(Icons.Outlined.Star, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSetDefault()
                            },
                        )
                        if (isDefault) {
                            DropdownMenuItem(
                                text = { Text("Use demo engine instead") },
                                onClick = {
                                    menuOpen = false
                                    onClearDefault()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("File details") },
                            onClick = {
                                menuOpen = false
                                onDetails()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from library", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (model.isUsable) {
                    StatusPill("In library", MaterialTheme.colorScheme.secondary)
                } else {
                    StatusPill("Unsupported", MaterialTheme.colorScheme.error)
                }
                if (isDefault) {
                    StatusPill("Default", MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Not loaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }
    }
}

@Composable
private fun MilestoneNote() {
    InfoBanner(
        icon = Icons.Outlined.Info,
        tone = MaterialTheme.colorScheme.tertiary,
        text = "Loading, resource estimation and GGUF inference arrive in Milestone 2 with a native backend. " +
            "Chat currently runs the demo engine, so selecting a default model does not load it yet.",
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ModelDetailsDialog(model: LocalModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Format", model.format.name)
                DetailRow("Quantization", model.quantization ?: "not detected")
                DetailRow("Size", if (model.sizeBytes > 0) formatFileSize(model.sizeBytes) else "unknown")
                DetailRow("State", "not loaded")
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
                if (!model.isUsable) {
                    Text(
                        text = "Only .gguf files can be used by the planned backend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMindExtras.current.mutedText,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
