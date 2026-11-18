package com.localmind.app.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.app.core.domain.model.LocalModel
import com.localmind.app.core.ui.components.InfoBanner
import com.localmind.app.core.ui.components.MetaChip
import com.localmind.app.core.ui.theme.LocalMindExtras
import com.localmind.app.core.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<LocalModel>,
    activeModelId: String?,
    isDemoEngine: Boolean,
    onSelect: (String?) -> Unit,
    onManageModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extras = LocalMindExtras.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Inference engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Select the model used for new responses",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.mutedText,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            if (isDemoEngine) {
                InfoBanner(
                    icon = Icons.Outlined.Info,
                    text = "Milestone 1 ships a demo engine. Responses are simulated and do not use the selected file. " +
                        "The native GGUF backend arrives in Milestone 2.",
                )
                Spacer(Modifier.height(10.dp))
            }

            EngineRow(
                title = "Demo engine",
                subtitle = "Built in · streaming, stop, markdown",
                selected = activeModelId == null,
                onClick = {
                    onSelect(null)
                    onDismiss()
                },
            )

            models.forEach { model ->
                EngineRow(
                    title = model.displayName,
                    subtitle = if (model.isUsable) {
                        "${model.format.name}${model.quantization?.let { " · $it" }.orEmpty()} · ${formatFileSize(model.sizeBytes)}"
                    } else {
                        "Unsupported format · import a .gguf file"
                    },
                    selected = model.id == activeModelId,
                    enabled = model.isUsable,
                    onClick = {
                        onSelect(model.id)
                        onDismiss()
                    },
                )
            }

            if (models.isEmpty()) {
                Text(
                    text = "No imported models yet. Import a GGUF file from the Models tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.mutedText,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }

            TextButton(onClick = onManageModels) {
                Text("Manage models")
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val extras = LocalMindExtras.current
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else extras.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                MetaChip("active", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
