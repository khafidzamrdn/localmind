package com.localmind.app.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.localmind.app.core.domain.model.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = BlueMist,
    onPrimary = OnAccent,
    primaryContainer = BlueMistDeep,
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Teal,
    onSecondary = Color(0xFF06201D),
    secondaryContainer = Color(0xFF1B3B38),
    onSecondaryContainer = Color(0xFFB8F0E8),
    tertiary = Violet,
    onTertiary = Color(0xFF21143A),
    tertiaryContainer = Color(0xFF3A2C55),
    onTertiaryContainer = Color(0xFFE8D9FF),
    background = Ink,
    onBackground = TextPrimary,
    surface = InkRaised,
    onSurface = TextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceHigh,
    surfaceContainerLow = Color(0xFF0F1318),
    surfaceContainerLowest = Ink,
    outline = InkBorder,
    outlineVariant = Color(0xFF202833),
    error = ErrorRed,
    onError = Color(0xFF3B0908),
    errorContainer = Color(0xFF4E1F1C),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,
    inversePrimary = BlueDeep,
    scrim = Color.Black,
)

private val LightScheme = lightColorScheme(
    primary = BlueDeep,
    onPrimary = Color.White,
    primaryContainer = BlueDeepContainer,
    onPrimaryContainer = Color(0xFF0A1F33),
    secondary = TealDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9EFEA),
    onSecondaryContainer = Color(0xFF052C28),
    tertiary = VioletDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DDFF),
    onTertiaryContainer = Color(0xFF21143A),
    background = Paper,
    onBackground = TextDark,
    surface = PaperRaised,
    onSurface = TextDark,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = TextDarkSecondary,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceHigh,
    surfaceContainerLow = Color(0xFFF2F5F9),
    surfaceContainerLowest = Color.White,
    outline = PaperBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = ErrorDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = TextDark,
    inverseOnSurface = Paper,
    inversePrimary = BlueMist,
    scrim = Color.Black,
)

/**
 * Extra semantic roles that Material 3 does not model directly. Kept in a
 * composition local so components do not hardcode dark-only colours.
 */
data class LocalMindExtraColors(
    val codeBackground: Color,
    val border: Color,
    val mutedText: Color,
    val userBubble: Color,
    val success: Color,
    val warning: Color,
)

private val DarkExtras = LocalMindExtraColors(
    codeBackground = Color(0xFF0E1217),
    border = InkBorder,
    mutedText = TextMuted,
    userBubble = Color(0xFF233A55),
    success = Color(0xFF8FD8A8),
    warning = Color(0xFFF2C879),
)

private val LightExtras = LocalMindExtraColors(
    codeBackground = Color(0xFFF3F6FA),
    border = PaperBorder,
    mutedText = TextDarkSecondary,
    userBubble = Color(0xFFDCE9F8),
    success = Color(0xFF1F7A4D),
    warning = Color(0xFF8A5A00),
)

val LocalMindExtras = staticCompositionLocalOf { DarkExtras }

@Composable
fun LocalMindTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val extras = if (darkTheme) DarkExtras else LightExtras

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalMindExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LocalMindTypography,
            shapes = LocalMindShapes,
            content = content,
        )
    }
}
