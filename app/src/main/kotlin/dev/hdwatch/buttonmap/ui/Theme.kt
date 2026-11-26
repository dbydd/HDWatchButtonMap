package dev.hdwatch.buttonmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.hdwatch.buttonmap.config.ThemePreset

/**
 * Console palette: every surface is a gradient pair; flat fills are reserved
 * for hairlines, status pips, and glows.
 */
data class HdPalette(
    // Field: vignette from surface (center) down to edge (rim).
    val background: Color = Color(0xFF0A1418),
    val edge: Color = Color(0xFF04090B),
    val surface: Color = Color(0xFF10222A),
    // Glass panel: top-lit, bottom-deep.
    val panelTop: Color = Color(0xFF16303A),
    val panelBottom: Color = Color(0xFF0A1A21),
    // Primary metallic accent: bright face + deep shade for gradients.
    val primary: Color = Color(0xFFD2B071),
    val goldDeep: Color = Color(0xFF6F5527),
    val secondary: Color = Color(0xFFE7E1CF), // ivory
    val danger: Color = Color(0xFFC8553D),
    val muted: Color = Color(0xFF64808A),      // teal grey
    val text: Color = Color(0xFFF2EDDD),
    val onPrimary: Color = Color(0xFF10181C),
    val hairline: Color = Color(0x2AEFE9D8),   // 16% ivory structural line
)

fun paletteFor(preset: ThemePreset): HdPalette = when (preset) {
    ThemePreset.OROKIN -> HdPalette()

    ThemePreset.TACTICAL -> HdPalette(
        background = Color(0xFF0B0C0E),
        edge = Color(0xFF030405),
        surface = Color(0xFF16181D),
        panelTop = Color(0xFF20242C),
        panelBottom = Color(0xFF101216),
        primary = Color(0xFFF0C419),
        goldDeep = Color(0xFF7D6208),
        secondary = Color(0xFFE8E6DE),
        danger = Color(0xFFE04F3E),
        muted = Color(0xFF757D8A),
        text = Color(0xFFF5F3EC),
        onPrimary = Color(0xFF0D0F12),
        hairline = Color(0x2AF2F0E6),
    )

    ThemePreset.PHOSPHOR -> HdPalette(
        background = Color(0xFF050B0D),
        edge = Color(0xFF000000),
        surface = Color(0xFF0B191E),
        panelTop = Color(0xFF0F262C),
        panelBottom = Color(0xFF061317),
        primary = Color(0xFF63DFC2),
        goldDeep = Color(0xFF1F6E5D),
        secondary = Color(0xFFD4EFE9),
        danger = Color(0xFFE35D52),
        muted = Color(0xFF4F7B78),
        text = Color(0xFFE8FBF6),
        onPrimary = Color(0xFF051310),
        hairline = Color(0x28B8F2E4),
    )
}

val LocalHdPalette = staticCompositionLocalOf { HdPalette() }

/**
 * The app draws itself on plain compose foundation (no material widget set).
 * Wear OS material3 components can join later without fighting a mobile theme.
 */
@Composable
fun HDTheme(
    preset: ThemePreset = ThemePreset.OROKIN,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(preset)
    CompositionLocalProvider(LocalHdPalette provides palette) {
        content()
    }
}
