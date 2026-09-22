package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Helldivers-2 console palette: deep space navy field, bright command gold,
 * glass panels lit from their top edge. Every surface is a gradient pair;
 * flat fills are reserved for hairlines and glows.
 */
data class HdPalette(
    // Field: vignette from surface (center) down to edge (rim).
    val background: Color = Color(0xFF0A0E15),
    val edge: Color = Color(0xFF05070C),
    val surface: Color = Color(0xFF151D2A),
    // Glass panel: top-lit, bottom-deep.
    val panelTop: Color = Color(0xFF1E2937),
    val panelBottom: Color = Color(0xFF0E141E),
    // Command gold: bright face, deep shade for metallic gradients.
    val primary: Color = Color(0xFFF0C454),
    val goldDeep: Color = Color(0xFF8A6524),
    val secondary: Color = Color(0xFFC9D3DF), // ice silver
    val danger: Color = Color(0xFFE0645C),
    val muted: Color = Color(0xFF6E7A8A),
    val text: Color = Color(0xFFEAF0F7),
    val onPrimary: Color = Color(0xFF12161D),
    val hairline: Color = Color(0x2AFFFFFF), // 16% white structural line
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
