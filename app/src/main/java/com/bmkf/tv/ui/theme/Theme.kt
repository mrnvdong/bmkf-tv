package com.bmkf.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CinemaBackground = Color(0xFF0B0D14)
val CinemaSurface = Color(0xFF141824)
val CinemaSurfaceElevated = Color(0xFF1D2335)
val CinemaAccent = Color(0xFFE50914)
val CinemaGold = Color(0xFFF5C518)
val CinemaTextPrimary = Color(0xFFF8FAFC)
val CinemaTextSecondary = Color(0xFFCBD5E1)
val CinemaTextMuted = Color(0xFF94A3B8)
val CinemaBorderSubtle = Color(0xFF283046)

private val CinemaColorScheme = darkColorScheme(
    primary = CinemaAccent,
    onPrimary = Color.White,
    secondary = CinemaGold,
    onSecondary = CinemaBackground,
    background = CinemaBackground,
    onBackground = CinemaTextPrimary,
    surface = CinemaSurface,
    onSurface = CinemaTextPrimary,
    surfaceVariant = CinemaSurfaceElevated,
    onSurfaceVariant = CinemaTextSecondary,
    outline = CinemaBorderSubtle
)

@Composable
fun BmkfTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CinemaColorScheme,
        content = content
    )
}
