package com.localmind.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Chat("chat", "Chat", Icons.Outlined.ChatBubbleOutline),
    Projects("projects", "Projects", Icons.Outlined.Folder),
    Models("models", "Models", Icons.Outlined.Memory),
    Settings("settings", "Settings", Icons.Outlined.Settings),
}
