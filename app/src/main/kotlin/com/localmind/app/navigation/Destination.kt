package com.localmind.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.localmind.app.core.i18n.UiKey

enum class Destination(
    val route: String,
    val labelKey: UiKey,
    val icon: ImageVector,
) {
    Chat("chat", UiKey.navChat, Icons.Outlined.ChatBubbleOutline),
    Projects("projects", UiKey.navProjects, Icons.Outlined.Folder),
    Models("models", UiKey.navModels, Icons.Outlined.Memory),
    Settings("settings", UiKey.navSettings, Icons.Outlined.Settings),
}
