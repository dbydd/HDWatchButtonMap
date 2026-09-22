package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Orokin console palette: deep teal-black field, antique command gold, ivory
 * body text. Every surface is a gradient pair; flat fills are reserved for
 * hairlines and glows.
 */
data class HdPalette(
    // Field: vignette from surface (center) down to edge (rim).
    val background: Color = Color(0xFF0A1418),
    val edge: Color = Color(0xFF04090B),
    val surface: Color = Color(0xFF10222A),
    // Glass panel: top-lit, bottom-deep.
    val panelTop: Color = Color(0xFF16303A),
    val panelBottom: Color = Color(0xFF0A1A21),
    // Orokin gold: bright face, deep shade for metallic gradients.
    val primary: Color = Color(0xFFD2B071),
    val goldDeep: Color = Color(0xFF6F5527),
    val secondary: Color = Color(0xFFE7E1CF), // ivory
    val danger: Color = Color(0xFFC8553D),
    val muted: Color = Color(0xFF64808A),      // teal grey
    val text: Color = Color(0xFFF2EDDD),
    val onPrimary: Color = Color(0xFF10181C),
    val hairline: Color = Color(0x2AEFE9D8),   // 16% ivory structural line
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
