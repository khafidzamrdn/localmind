package com.localmind.app.feature.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.localmind.app.core.ui.components.EmptyState
import com.localmind.app.core.ui.components.InfoBanner
import com.localmind.app.core.ui.components.ScreenHeader
import com.localmind.app.core.ui.components.ScreenSectionTitle
import com.localmind.app.core.ui.components.StatusPill
import com.localmind.app.core.ui.theme.LocalMindExtras

private data class RoadmapItem(
    val stage: String,
    val title: String,
    val description: String,
    val state: String,
    val tone: Color,
    val icon: ImageVector,
)

@Composable
fun ProjectsScreen(modifier: Modifier = Modifier) {
    val extras = LocalMindExtras.current
    val roadmap = listOf(
        RoadmapItem(
            stage = "M1",
            title = "Foundation",
            description = "Chat UI, Room storage, streaming demo backend, model registry.",
            state = "current",
            tone = MaterialTheme.colorScheme.primary,
            icon = Icons.Outlined.Folder,
        ),
        RoadmapItem(
            stage = "M3",
            title = "Agent runtime",
            description = "Tool registry, structured tool protocol, permission engine and task panel.",
            state = "next",
            tone = MaterialTheme.colorScheme.tertiary,
            icon = Icons.Outlined.Lock,
        ),
        RoadmapItem(
            stage = "M4",
            title = "Workspace",
            description = "SAF folder picker, persisted grants, file explorer and project tree.",
            state = "planned",
            tone = extras.mutedText,
            icon = Icons.Outlined.AccountTree,
        ),
        RoadmapItem(
            stage = "M5",
            title = "Coding tools",
            description = "Read, create, edit and search project files with diff previews and approval.",
            state = "planned",
            tone = extras.mutedText,
            icon = Icons.Outlined.Search,
        ),
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = "Projects",
                subtitle = "Workspace-scoped context for coding tasks",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
        }
        item(key = "empty") {
            EmptyState(
                icon = Icons.Outlined.Folder,
                title = "No workspace yet",
                body = "Projects let the assistant read and modify files inside a folder you explicitly grant. " +
                    "Workspace access is scoped; LocalMind will never scan your whole device.",
                modifier = Modifier.padding(top = 26.dp, bottom = 10.dp),
            )
        }
        item(key = "banner") {
            InfoBanner(
                icon = Icons.Outlined.Info,
                text = "Milestone 4 adds folder import through the Android Storage Access Framework with persisted " +
                    "read grants. No project tools are enabled before that.",
            )
        }
        item(key = "roadmap-title") {
            ScreenSectionTitle("Roadmap")
        }
        items(roadmap.size, key = { "roadmap-$it" }) { index ->
            val item = roadmap[index]
            RoadmapCard(item)
        }
        item(key = "footer") {
            Row(
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = extras.mutedText,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Workspace tools require explicit approval and never run outside the granted folder.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
            }
        }
    }
}

@Composable
private fun RoadmapCard(item: RoadmapItem) {
    val extras = LocalMindExtras.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = item.tone,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${item.stage} · ${item.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(item.state, item.tone)
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.mutedText,
                )
            }
        }
    }
}
