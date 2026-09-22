package dev.hdwatch.buttonmap.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppNav = compositionLocalOf<AppNav> { error("AppNav not provided") }

/**
 * Capabilities that need the Activity (intents, SAF, permission prompts).
 * The host activity swaps in the real implementation.
 */
data class PlatformActions(
    val importFile: () -> Unit = {},
    val exportFile: () -> Unit = {},
    val requestDiscoverable: () -> Unit = {},
    val requestBluetoothPermissions: () -> Unit = {},
)

val LocalPlatformActions = staticCompositionLocalOf { PlatformActions() }

val LocalHaptics = staticCompositionLocalOf { Haptics(null) }
