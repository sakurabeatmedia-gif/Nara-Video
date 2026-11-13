package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NaraDarkColorScheme = darkColorScheme(
    primary = NaraPrimary,
    onPrimary = Color.White,
    primaryContainer = NaraPrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = NaraSecondary,
    onSecondary = Color(0xFF121622),
    secondaryContainer = NaraSurfaceVariant,
    onSecondaryContainer = NaraTextPrimary,
    tertiary = NaraGold,
    onTertiary = Color.Black,
    background = NaraBackground,
    onBackground = NaraTextPrimary,
    surface = NaraSurface,
    onSurface = NaraTextPrimary,
    surfaceVariant = NaraSurfaceVariant,
    onSurfaceVariant = NaraTextSecondary,
    outline = NaraBorder,
    error = NaraRed,
    onError = Color.White
)

@Composable
fun NaraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NaraDarkColorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatibility alias
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    NaraTheme(content = content)
}
