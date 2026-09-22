package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class HdPalette(
    // Precious-metal scheme: gunmetal field, silver body, gold accents.
    val background: Color = Color(0xFF14181E),
    val surface: Color = Color(0xFF1D232C),
    val primary: Color = Color(0xFFD9B45B),   // gold
    val secondary: Color = Color(0xFFC9D1DB), // silver
    val danger: Color = Color(0xFFD0605A),
    val muted: Color = Color(0xFF7E8794),
    val text: Color = Color(0xFFE6EAF0),      // bright silver
    val onPrimary: Color = Color(0xFF171B21),
)

val LocalHdPalette = staticCompositionLocalOf { HdPalette() }

/**
 * The app draws itself on plain compose foundation (no material widget set).
 * Wear OS material3 components can join later without fighting a mobile theme.
 */
@Composable
fun HDTheme(content: @Composable () -> Unit) {
    // Wear watch UI is always dark; isSystemInDarkTheme kept for tooling parity.
    val palette = if (isSystemInDarkTheme()) HdPalette() else HdPalette()
    CompositionLocalProvider(LocalHdPalette provides palette) {
        content()
    }
}
