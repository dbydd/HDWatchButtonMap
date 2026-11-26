package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ThemePreset
import dev.hdwatch.buttonmap.input.CapStatus
import dev.hdwatch.buttonmap.input.GestureBackend
import dev.hdwatch.buttonmap.input.Symbol
import dev.hdwatch.buttonmap.input.WatchDefaults
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Refactored 2-level Settings architecture for small watch displays:
 *  - [SettingsScreen]: scannable Hub with a 3-button quick-toggle dock
 *    (Vibration / Keep Awake / Keep-Alive), 5 category cards with live value
 *    summaries, and the 5-tap version easter egg.
 *  - [SettingsDetailScreen]: 5 focused sub-pages (Display, Crown & Bezel,
 *    Sequence & Timing, Gesture Sensors, Config & System) so every screen stays
 *    compact and effortless to navigate on a wrist.
 */

private val GESTURE_SLOTS = listOf(
    Symbol.GESTURE_1,
    Symbol.GESTURE_2,
    Symbol.GESTURE_3,
    Symbol.GESTURE_4,
)

@Composable
fun SettingsScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val palette = LocalHdPalette.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    val repoStatus by app.configRepo.status.collectAsState()
    val gestures = app.gestures as GestureBackend
    val caps = remember(settings.gestureSensorsEnabled) {
        gestures.refresh()
        gestures.capabilities()
    }

    val themeName = themeTitle(settings.theme)
    val displaySummary = stringResource(
        R.string.set_hub_sum_display,
        themeName,
        stringResource(if (settings.showDirectKeyLabels) R.string.set_hub_on else R.string.set_hub_off),
    )

    val effectiveThreshold = config.effectiveRotateThreshold()
    val clickStr = pluralStringResource(R.plurals.set_step_value_clicks, effectiveThreshold, effectiveThreshold)
    val scaleStr = String.format(Locale.US, "%.2g×", settings.rotaryScale)
    val rotarySummary = if (settings.invertRotation) {
        stringResource(R.string.set_hub_sum_rotary_inv, clickStr, scaleStr)
    } else {
        stringResource(R.string.set_hub_sum_rotary, clickStr, scaleStr)
    }

    val timeoutSec = String.format(Locale.US, "%.1fs", settings.sequenceTimeoutMs / 1000f)
    val jitterStr = if (settings.keyJitterMs == 0L) {
        stringResource(R.string.set_val_off)
    } else {
        "${settings.keyJitterMs}ms"
    }
    val timingSummary = stringResource(R.string.set_hub_sum_timing, timeoutSec, jitterStr)

    val boundCount = caps.count { cap ->
        settings.sensorToSymbol[cap.id]?.let { Symbol.fromCode(it) } != null
    }
    val gestureSummary = if (!settings.gestureSensorsEnabled) {
        stringResource(R.string.set_hub_sum_gestures_off)
    } else {
        stringResource(R.string.set_hub_sum_gestures_on, boundCount, caps.size)
    }

    ScreenScaffold(title = stringResource(R.string.set_title)) {
        QuickToggleDock(
            items = listOf(
                QuickToggleItem(
                    label = stringResource(R.string.set_quick_vib),
                    checked = settings.vibrationEnabled,
                    onToggle = {
                        app.configRepo.updateSettings { it.copy(vibrationEnabled = !it.vibrationEnabled) }
                    },
                ),
                QuickToggleItem(
                    label = stringResource(R.string.set_quick_awake),
                    checked = settings.keepScreenOn,
                    onToggle = {
                        app.configRepo.updateSettings { it.copy(keepScreenOn = !it.keepScreenOn) }
                    },
                ),
                QuickToggleItem(
                    label = stringResource(R.string.set_quick_alive),
                    checked = settings.keepAliveService,
                    onToggle = {
                        app.configRepo.updateSettings { it.copy(keepAliveService = !it.keepAliveService) }
                    },
                ),
            ),
        )

        SettingsCategoryRow(
            glyph = "◆",
            title = stringResource(R.string.set_cat_display),
            summary = displaySummary,
        ) { nav.push(Route.SettingsDetail(SettingsSection.DISPLAY)) }

        SettingsCategoryRow(
            glyph = "↻",
            title = stringResource(R.string.set_cat_rotary),
            summary = rotarySummary,
            accent = config.activeProfile.rotateThreshold != null,
        ) { nav.push(Route.SettingsDetail(SettingsSection.ROTARY)) }

        SettingsCategoryRow(
            glyph = "»",
            title = stringResource(R.string.set_cat_timing),
            summary = timingSummary,
        ) { nav.push(Route.SettingsDetail(SettingsSection.TIMING)) }

        SettingsCategoryRow(
            glyph = "◎",
            title = stringResource(R.string.set_cat_gestures),
            summary = gestureSummary,
            accent = settings.gestureSensorsEnabled,
        ) { nav.push(Route.SettingsDetail(SettingsSection.GESTURES)) }

        SettingsCategoryRow(
            glyph = "≡",
            title = stringResource(R.string.set_cat_config),
            summary = repoStatus,
        ) { nav.push(Route.SettingsDetail(SettingsSection.CONFIG)) }

        // ---- easter egg: five taps on the build label unlock the HD2 pack ----
        VersionEasterEgg(palette = palette)
    }
}

@Composable
fun SettingsDetailScreen(section: SettingsSection) {
    when (section) {
        SettingsSection.DISPLAY -> DisplaySettingsPage()
        SettingsSection.ROTARY -> RotarySettingsPage()
        SettingsSection.TIMING -> TimingSettingsPage()
        SettingsSection.GESTURES -> GestureSettingsPage()
        SettingsSection.CONFIG -> ConfigSettingsPage()
    }
}

// ------------------------------------------------------------------ Sub-pages

@Composable
private fun DisplaySettingsPage() {
    val app = HdApp.instance
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings

    ScreenScaffold(title = stringResource(R.string.set_cat_display)) {
        SectionLabel(stringResource(R.string.set_theme_label))
        PaletteCard(
            preset = ThemePreset.OROKIN,
            title = stringResource(R.string.set_theme_orokin),
            subtitle = stringResource(R.string.set_theme_orokin_desc),
            selected = settings.theme == ThemePreset.OROKIN,
            onSelect = {
                haptics.tick()
                app.configRepo.updateSettings { it.copy(theme = ThemePreset.OROKIN) }
            },
        )
        PaletteCard(
            preset = ThemePreset.TACTICAL,
            title = stringResource(R.string.set_theme_tactical),
            subtitle = stringResource(R.string.set_theme_tactical_desc),
            selected = settings.theme == ThemePreset.TACTICAL,
            onSelect = {
                haptics.tick()
                app.configRepo.updateSettings { it.copy(theme = ThemePreset.TACTICAL) }
            },
        )
        PaletteCard(
            preset = ThemePreset.PHOSPHOR,
            title = stringResource(R.string.set_theme_phosphor),
            subtitle = stringResource(R.string.set_theme_phosphor_desc),
            selected = settings.theme == ThemePreset.PHOSPHOR,
            onSelect = {
                haptics.tick()
                app.configRepo.updateSettings { it.copy(theme = ThemePreset.PHOSPHOR) }
            },
        )

        SectionLabel(stringResource(R.string.set_section_display_haptics))
        SwitchRow(
            label = stringResource(R.string.set_switch_keep_screen_on),
            checked = settings.keepScreenOn,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(keepScreenOn = on) } },
            hint = stringResource(R.string.set_switch_keep_screen_on_hint),
        )
        SwitchRow(
            label = stringResource(R.string.set_switch_direct_labels),
            checked = settings.showDirectKeyLabels,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(showDirectKeyLabels = on) } },
            hint = stringResource(R.string.set_switch_direct_labels_hint),
        )
        SwitchRow(
            label = stringResource(R.string.set_switch_vibration),
            checked = settings.vibrationEnabled,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(vibrationEnabled = on) } },
            hint = stringResource(R.string.set_switch_vibration_hint),
        )
    }
}

@Composable
private fun RotarySettingsPage() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    val profile = config.activeProfile
    val hwProfile = remember { WatchDefaults.detect() }
    var calibratedFlash by remember { mutableStateOf(false) }

    LaunchedEffect(calibratedFlash) {
        if (calibratedFlash) {
            delay(1400)
            calibratedFlash = false
        }
    }

    ScreenScaffold(title = stringResource(R.string.set_cat_rotary)) {
        // Detected watch banner + one-tap auto-calibration
        HdPanel(
            modifier = Modifier
                .settingsCardWidth()
                .padding(vertical = 2.5.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    text = stringResource(R.string.set_watch_detected, hwProfile.label),
                    fontFamily = FontFamily.Monospace,
                    color = palette.primary,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                ActionButton(
                    text = stringResource(
                        if (calibratedFlash) R.string.set_action_auto_watch_done else R.string.set_action_auto_watch,
                    ),
                    tone = HdTone.Accent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        haptics.tick()
                        app.configRepo.updateSettings {
                            it.copy(
                                invertRotation = hwProfile.invertRotation,
                                rotateThreshold = hwProfile.rotateThreshold,
                                rotaryScale = hwProfile.rotaryScale,
                            )
                        }
                        calibratedFlash = true
                    },
                )
            }
        }

        SectionLabel(stringResource(R.string.set_section_rotation))
        SwitchRow(
            label = stringResource(R.string.set_switch_invert),
            checked = settings.invertRotation,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(invertRotation = on) } },
            hint = if (settings.invertRotation) {
                stringResource(R.string.set_switch_invert_on)
            } else {
                stringResource(R.string.set_switch_invert_off)
            },
        )

        StepperCard(
            label = stringResource(R.string.set_step_rotate_threshold),
            value = settings.rotateThreshold.toLong(),
            displayValue = pluralStringResource(
                R.plurals.set_step_value_clicks,
                settings.rotateThreshold,
                settings.rotateThreshold,
            ),
            min = 1,
            max = 15,
            step = 1,
            onChange = { v -> app.configRepo.updateSettings { it.copy(rotateThreshold = v.toInt()) } },
        )

        // Single clean toggle replaces two confusingly stacked threshold steppers.
        val profileOverride = profile.rotateThreshold
        val hasOverride = profileOverride != null
        SwitchRow(
            label = stringResource(R.string.set_switch_profile_override),
            checked = hasOverride,
            onCheckedChange = { enable ->
                app.configRepo.updateProfile(profile.id) {
                    it.copy(rotateThreshold = if (enable) settings.rotateThreshold else null)
                }
            },
            hint = if (hasOverride) {
                stringResource(R.string.set_profile_override_on, profile.name)
            } else {
                stringResource(R.string.set_profile_override_off, profile.name, settings.rotateThreshold)
            },
        )
        if (profileOverride != null) {
            StepperCard(
                label = stringResource(R.string.set_step_profile_threshold),
                value = profileOverride.toLong(),
                displayValue = pluralStringResource(R.plurals.set_step_value_clicks, profileOverride, profileOverride),
                min = 1,
                max = 15,
                step = 1,
                onChange = { v ->
                    app.configRepo.updateProfile(profile.id) { it.copy(rotateThreshold = v.toInt()) }
                },
            )
        }

        val scalePct = (settings.rotaryScale * 100f).roundToInt().toLong().coerceIn(25L, 400L)
        StepperCard(
            label = stringResource(R.string.set_step_rotary_scale),
            value = scalePct,
            displayValue = String.format(Locale.US, "%.2f×", scalePct / 100f),
            min = 25,
            max = 400,
            step = 25,
            hint = stringResource(R.string.set_step_rotary_scale_hint),
            onChange = { v -> app.configRepo.updateSettings { it.copy(rotaryScale = v / 100f) } },
        )

        StepperCard(
            label = stringResource(R.string.set_step_latch_debounce),
            value = settings.rotaryLatchDebounceMs,
            displayValue = if (settings.rotaryLatchDebounceMs == 0L) {
                stringResource(R.string.set_val_off)
            } else {
                stringResource(R.string.set_step_value_ms, settings.rotaryLatchDebounceMs)
            },
            min = 0,
            max = 1500,
            step = 100,
            hint = stringResource(R.string.set_step_latch_debounce_hint),
            onChange = { v -> app.configRepo.updateSettings { it.copy(rotaryLatchDebounceMs = v) } },
        )
    }
}

@Composable
private fun TimingSettingsPage() {
    val app = HdApp.instance
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings

    ScreenScaffold(title = stringResource(R.string.set_cat_timing)) {
        StepperCard(
            label = stringResource(R.string.set_step_sequence_timeout),
            value = settings.sequenceTimeoutMs,
            displayValue = String.format(Locale.US, "%.1f s", settings.sequenceTimeoutMs / 1000f),
            min = 1000,
            max = 8000,
            step = 500,
            hint = stringResource(R.string.set_step_sequence_timeout_hint),
            onChange = { v -> app.configRepo.updateSettings { it.copy(sequenceTimeoutMs = v) } },
        )
        StepperCard(
            label = stringResource(R.string.set_step_key_jitter),
            value = settings.keyJitterMs,
            displayValue = if (settings.keyJitterMs == 0L) {
                stringResource(R.string.set_val_off)
            } else {
                stringResource(R.string.set_step_value_ms, settings.keyJitterMs)
            },
            min = 0,
            max = 100,
            step = 5,
            hint = stringResource(R.string.set_step_key_jitter_hint),
            onChange = { v -> app.configRepo.updateSettings { it.copy(keyJitterMs = v) } },
        )
        StepperCard(
            label = stringResource(R.string.set_step_text_delay),
            value = settings.textDelayMs,
            displayValue = stringResource(R.string.set_step_value_ms, settings.textDelayMs),
            min = 10,
            max = 200,
            step = 10,
            hint = stringResource(R.string.set_step_text_delay_hint),
            onChange = { v -> app.configRepo.updateSettings { it.copy(textDelayMs = v) } },
        )
    }
}

@Composable
private fun GestureSettingsPage() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    val gestures = app.gestures as GestureBackend
    var scanNonce by remember { mutableStateOf(0) }
    val caps = remember(settings.gestureSensorsEnabled, scanNonce) {
        gestures.refresh()
        gestures.capabilities()
    }

    ScreenScaffold(title = stringResource(R.string.set_cat_gestures)) {
        SwitchRow(
            label = stringResource(R.string.set_switch_gestures),
            checked = settings.gestureSensorsEnabled,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(gestureSensorsEnabled = on) }
                // Apply immediately instead of waiting until the user leaves settings.
                app.gestures.refresh()
                app.gestures.stop()
                if (on) app.gestures.start()
            },
            hint = stringResource(R.string.set_switch_gestures_hint),
        )

        SectionLabel(stringResource(R.string.set_section_gesture_slots))
        if (caps.isEmpty()) {
            Text(
                text = stringResource(R.string.set_gestures_none),
                color = palette.muted,
                fontSize = 9.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        caps.forEach { cap ->
            // Read directly from settings StateFlow so bindings never drift out of sync.
            val bound = settings.sensorToSymbol[cap.id]?.let { Symbol.fromCode(it) }
            GestureCapabilityRow(
                label = cap.label,
                status = cap.status,
                bound = bound,
                onClick = {
                    val order = GESTURE_SLOTS + listOf<Symbol?>(null)
                    val next = order[(order.indexOf(bound) + 1) % order.size]
                    gestures.bind(cap.id, next)
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        ActionButton(
            text = stringResource(R.string.set_action_rescan_sensors),
            modifier = Modifier.settingsCardWidth(),
            onClick = {
                haptics.tick()
                app.gestures.refresh()
                if (settings.gestureSensorsEnabled) {
                    app.gestures.stop()
                    app.gestures.start()
                }
                scanNonce++
            },
        )
    }
}

@Composable
private fun ConfigSettingsPage() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val actions = LocalPlatformActions.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    val status by app.configRepo.status.collectAsState()
    val statusIsError by app.configRepo.statusIsError.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            delay(3500)
            confirmReset = false
        }
    }

    ScreenScaffold(title = stringResource(R.string.set_cat_config)) {
        SectionLabel(stringResource(R.string.set_section_system))
        SwitchRow(
            label = stringResource(R.string.set_switch_keep_alive),
            checked = settings.keepAliveService,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(keepAliveService = on) } },
            hint = stringResource(R.string.set_switch_keep_alive_hint),
        )
        SwitchRow(
            label = stringResource(R.string.set_switch_capture_unknown),
            checked = settings.captureUnknownInput,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(captureUnknownInput = on) } },
            hint = stringResource(R.string.set_switch_capture_unknown_hint),
        )

        SectionLabel(stringResource(R.string.set_section_config))
        HdPanel(
            modifier = Modifier
                .settingsCardWidth()
                .padding(vertical = 2.5.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height(18.dp)
                        .background(
                            if (statusIsError) palette.danger else palette.primary.copy(alpha = 0.7f),
                            RoundedCornerShape(1.5.dp),
                        ),
                )
                Text(
                    text = status,
                    color = if (statusIsError) palette.danger else palette.secondary,
                    fontSize = 9.5.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        ActionGridRow(
            leftText = stringResource(R.string.set_action_reload),
            onLeft = {
                haptics.tick()
                app.configRepo.reload()
            },
            rightText = stringResource(R.string.set_action_import),
            onRight = { actions.importFile() },
        )
        ActionGridRow(
            leftText = stringResource(R.string.set_action_export),
            onLeft = { actions.exportFile() },
            rightText = stringResource(R.string.set_action_write_external),
            onRight = {
                haptics.tick()
                app.configRepo.copyToExternal()
            },
        )

        Spacer(Modifier.height(6.dp))
        ActionButton(
            text = stringResource(
                if (confirmReset) R.string.set_action_reset_confirm else R.string.set_action_reset_defaults,
            ),
            tone = HdTone.Danger,
            modifier = Modifier.settingsCardWidth(),
            onClick = {
                if (confirmReset) {
                    confirmReset = false
                    app.configRepo.resetToDefaults()
                    app.gestures.refresh()
                    app.gestures.stop()
                    app.gestures.start()
                    haptics.macro()
                } else {
                    confirmReset = true
                    haptics.tick()
                }
            },
        )
    }
}

// ------------------------------------------------------------------ Helpers

@Composable
private fun VersionEasterEgg(palette: HdPalette) {
    val app = HdApp.instance
    val version = remember {
        runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    var taps by remember { mutableStateOf(0) }
    var unlockedMsg by remember { mutableStateOf(false) }

    LaunchedEffect(taps) {
        if (taps in 1..4) {
            delay(1600)
            taps = 0
        }
    }
    LaunchedEffect(unlockedMsg) {
        if (unlockedMsg) {
            delay(2200)
            unlockedMsg = false
        }
    }

    val dots = if (taps >= 2) "  " + "·".repeat(taps) else ""
    Text(
        text = if (unlockedMsg) {
            stringResource(R.string.set_easter_unlocked)
        } else {
            stringResource(R.string.set_version_line, version) + dots
        },
        color = if (unlockedMsg || taps >= 2) palette.primary else palette.muted.copy(alpha = 0.6f),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 2.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    taps += 1
                    if (taps >= 5) {
                        taps = 0
                        if (app.configRepo.installBundledPack("hd2_pack.json")) {
                            // Activate pack's gesture wiring immediately.
                            app.gestures.refresh()
                            app.gestures.stop()
                            app.gestures.start()
                            app.haptics.macro()
                            unlockedMsg = true
                        } else {
                            app.haptics.error()
                        }
                    }
                    waitForUpOrCancellation()
                }
            },
    )
}

@Composable
private fun themeTitle(preset: ThemePreset): String = when (preset) {
    ThemePreset.OROKIN -> stringResource(R.string.set_theme_orokin)
    ThemePreset.TACTICAL -> stringResource(R.string.set_theme_tactical)
    ThemePreset.PHOSPHOR -> stringResource(R.string.set_theme_phosphor)
}
