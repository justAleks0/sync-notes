package com.justaleks.syncnotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matches the palette in Website/src/index.css so all three clients look like one app.
private val SyncNotesColors = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    onPrimary = Color.White,
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF161920),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1D212A),
    onSurfaceVariant = Color(0xFF8B93A5),
    outline = Color(0xFF272C37),
    error = Color(0xFFFF6B6B),
)

@Composable
fun SyncNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SyncNotesColors, content = content)
}
