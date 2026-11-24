package com.mangastudio.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C8CFF),
    onPrimary = Color(0xFF0E1438),
    primaryContainer = Color(0xFF28326B),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF38D9A9),
    onSecondary = Color(0xFF003828),
    secondaryContainer = Color(0xFF0F4D3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF3E2400),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF9BA4B5),
    outline = Color(0xFF30363D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF420006)
)

@Composable
fun MangaStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        content = content
    )
}
