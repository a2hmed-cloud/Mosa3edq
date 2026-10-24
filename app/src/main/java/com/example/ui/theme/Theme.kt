package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = NavyBackground,
    secondary = CyanSecondary,
    onSecondary = NavyBackground,
    tertiary = BlueAccent,
    background = NavyBackground,
    onBackground = TextWhite,
    surface = NavySurface,
    onSurface = TextWhite,
    surfaceVariant = NavyCard,
    onSurfaceVariant = TextMuted,
    error = ErrorRed,
    onError = TextWhite
)

@Composable
fun YaMosaedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

// For backwards compatibility
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    YaMosaedTheme(content = content)
}
