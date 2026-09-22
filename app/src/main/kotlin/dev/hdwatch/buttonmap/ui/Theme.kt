package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Helldivers palette: dark steel, stratagem yellow, hazard red.
data class HdPalette(
    val background: Color = Color(0xFF070B10),
    val surface: Color = Color(0xFF101722),
    val primary: Color = Color(0xFFF2B705),
    val secondary: Color = Color(0xFFD4F12B),
    val danger: Color = Color(0xFFE2483D),
    val muted: Color = Color(0xFF8A97A8),
    val text: Color = Color(0xFFEDF2F7),
    val onPrimary: Color = Color(0xFF070B10),
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
