package com.deckos.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DeckBackground = Color(0xFF080C11)
val DeckSurface = Color(0xFF111821)
val DeckCyan = Color(0xFF6FE7DD)
val DeckMuted = Color(0xFF9FB0C0)
val DeckOutline = Color(0xFF263445)

private val DeckColors = darkColorScheme(
    primary = DeckCyan,
    onPrimary = Color(0xFF00201D),
    secondary = Color(0xFF9BB8FF),
    background = DeckBackground,
    onBackground = Color(0xFFE6EDF3),
    surface = DeckSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF182230),
    onSurfaceVariant = DeckMuted,
    outline = DeckOutline,
)

@Composable
fun DeckOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DeckColors,
        content = content,
    )
}
