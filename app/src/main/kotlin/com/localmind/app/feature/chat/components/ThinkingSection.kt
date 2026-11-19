package com.localmind.app.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.localmind.app.core.ui.theme.LocalMindExtras

/**
 * Collapsible reasoning block shown inside an assistant bubble.
 *
 * Completed messages start collapsed (unless there is no answer to show).
 * While a response streams, the block is expanded until the first visible
 * answer token arrives, then it folds itself away once so the answer gets the
 * space. The user can always expand it again.
 */
@Composable
fun ThinkingSection(
    reasoning: String,
    streaming: Boolean,
    hasAnswer: Boolean,
    stateKey: Any?,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    var expanded by rememberSaveable(stateKey) { mutableStateOf(!hasAnswer) }
    var autoCollapsed by rememberSaveable(stateKey) { mutableStateOf(false) }

    LaunchedEffect(streaming, hasAnswer) {
        if (!streaming) return@LaunchedEffect
        if (hasAnswer && !autoCollapsed) {
            expanded = false
            autoCollapsed = true
        } else if (!hasAnswer && !autoCollapsed) {
            expanded = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (streaming && !hasAnswer) "Thinking…" else "Thinking",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!streaming) {
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowUp
                } else {
                    Icons.Outlined.KeyboardArrowDown
                },
                contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )
        }
    }
}
