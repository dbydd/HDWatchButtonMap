package dev.hdwatch.buttonmap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.hdwatch.buttonmap.engine.InputSource
import dev.hdwatch.buttonmap.input.RotaryAccumulator
import dev.hdwatch.buttonmap.input.Symbol
import dev.hdwatch.buttonmap.ui.AppNav
import dev.hdwatch.buttonmap.ui.HDTheme
import dev.hdwatch.buttonmap.ui.HidScreen
import dev.hdwatch.buttonmap.ui.LocalAppNav
import dev.hdwatch.buttonmap.ui.LocalHaptics
import dev.hdwatch.buttonmap.ui.LocalPlatformActions
import dev.hdwatch.buttonmap.ui.LogScreen
import dev.hdwatch.buttonmap.ui.MacroListScreen
import dev.hdwatch.buttonmap.ui.MappingEditScreen
import dev.hdwatch.buttonmap.ui.MappingListScreen
import dev.hdwatch.buttonmap.ui.MenuScreen
import dev.hdwatch.buttonmap.ui.PadScreen
import dev.hdwatch.buttonmap.ui.PlatformActions
import dev.hdwatch.buttonmap.ui.Route
import dev.hdwatch.buttonmap.ui.SettingsScreen

/**
 * Single activity: routes raw input (keys, multi-stem hardware buttons, rotary
 * crown/bezel) into the sequence engine while on the pad screen, lets rotary
 * scroll menus when off-pad, manages screen wake-lock on the pad, and hands
 * platform capabilities + haptics down through CompositionLocals.
 */
class MainActivity : ComponentActivity() {

    private val app get() = HdApp.instance
    private val nav = AppNav()
    private val rotary = RotaryAccumulator()

    private val stemDownAt = HashMap<Int, Long>()

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { app.configRepo.importFrom(it) }
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { app.configRepo.exportTo(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()
        setContent {
            val config by app.configRepo.config.collectAsState()
            val keepAwake = nav.current == Route.Pad && config.settings.keepScreenOn
            LaunchedEffect(keepAwake) {
                if (keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            HDTheme(preset = config.settings.theme) {
                CompositionLocalProvider(
                    LocalAppNav provides nav,
                    LocalHaptics provides app.haptics,
                    LocalPlatformActions provides PlatformActions(
                        importFile = { openDocumentLauncher.launch(arrayOf("*/*")) },
                        exportFile = { createDocumentLauncher.launch("hdmap-export.json") },
                        requestDiscoverable = { launchDiscoverable() },
                        requestBluetoothPermissions = { requestBluetoothPermissions() },
                    ),
                ) {
                    Root()
                }
            }
        }
    }

    @Composable
    private fun Root() {
        when (val route = nav.current) {
            Route.Pad -> PadScreen()
            Route.Menu -> MenuScreen()
            Route.MappingList -> MappingListScreen()
            is Route.MappingEdit -> MappingEditScreen(route.symbol)
            Route.MacroList -> MacroListScreen()
            Route.Hid -> HidScreen()
            Route.Settings -> SettingsScreen()
            Route.Log -> LogScreen()
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        app.configRepo.checkExternalUpdate()
        app.gestures.start()
    }

    override fun onStop() {
        app.gestures.stop()
        stemDownAt.clear()
        rotary.reset()
        // A hold slot (CTRL) must never survive the pad going away.
        app.runner.emergencyRelease()
        app.engine.reset()
        nav.popToPad()
        super.onStop()
    }

    // ------------------------------------------------------------ key input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val settings = app.configRepo.config.value.settings

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (nav.current != Route.Pad && event.action == KeyEvent.ACTION_UP) {
                nav.pop()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Off the pad, the primary stem/side key acts as "back".
        if (nav.current != Route.Pad) {
            if (isPrimaryStemKey(event.keyCode) && event.action == KeyEvent.ACTION_UP) {
                nav.pop()
                return true
            }
            maybeLogUnknownKey(event, settings.captureUnknownInput)
            return super.dispatchKeyEvent(event)
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val stemPair = stemSymbolsFor(event.keyCode)
        if (stemPair != null) {
            if (isDown && event.repeatCount == 0) {
                stemDownAt[event.keyCode] = event.eventTime
            } else if (event.action == KeyEvent.ACTION_UP) {
                val started = stemDownAt.remove(event.keyCode) ?: event.eventTime
                val held = event.eventTime - started
                app.engine.feed(if (held >= LONG_PRESS_MS) stemPair.second else stemPair.first)
            }
            return true
        }

        val symbol = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Symbol.UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> Symbol.RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> Symbol.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Symbol.LEFT
            else -> null
        }
        if (symbol != null) {
            if (isDown && event.repeatCount == 0) app.engine.feed(symbol)
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP) nav.push(Route.Menu)
            return true
        }

        maybeLogUnknownKey(event, settings.captureUnknownInput)
        return super.dispatchKeyEvent(event)
    }

    private fun maybeLogUnknownKey(event: KeyEvent, capture: Boolean) {
        if (!capture || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        app.ring.log("KEY  ${KeyEvent.keyCodeToString(event.keyCode)} (power/voice keys usually never reach apps)")
    }

    private fun isPrimaryStemKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_STEM_PRIMARY || code == KeyEvent.KEYCODE_STEM_1

    /**
     * Multi-button watch mapping (Wear OS 3–6):
     *  - Primary / Stem 1 → S / SL
     *  - Secondary Stem 2 (TicWatch, OnePlus, GW Ultra action key, etc.) → G1 / G2
     *  - Tertiary Stem 3 → G3 / G4
     */
    private fun stemSymbolsFor(code: Int): Pair<Symbol, Symbol>? = when (code) {
        KeyEvent.KEYCODE_STEM_PRIMARY, KeyEvent.KEYCODE_STEM_1 -> Symbol.STEM to Symbol.STEM_LONG
        KeyEvent.KEYCODE_STEM_2 -> Symbol.GESTURE_1 to Symbol.GESTURE_2
        KeyEvent.KEYCODE_STEM_3 -> Symbol.GESTURE_3 to Symbol.GESTURE_4
        else -> null
    }

    // ---------------------------------------------------------- rotary input

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val deltaRaw = RotaryAccumulator.rotaryDelta(event)
        if (app.configRepo.config.value.settings.captureUnknownInput && deltaRaw != 0f) {
            app.ring.log(
                "GEN  act=${MotionEvent.actionToString(event.action)} " +
                    "src=0x${Integer.toHexString(event.source)} " +
                    "scroll=${event.getAxisValue(MotionEvent.AXIS_SCROLL)} " +
                    "v=${event.getAxisValue(MotionEvent.AXIS_VSCROLL)} " +
                    "h=${event.getAxisValue(MotionEvent.AXIS_HSCROLL)}",
            )
        }
        if (nav.current == Route.Pad && deltaRaw != 0f) {
            val cfg = app.configRepo.config.value
            val s = cfg.settings
            rotary.feed(
                event = event,
                threshold = cfg.effectiveRotateThreshold(),
                invert = s.invertRotation,
                scale = s.rotaryScale,
            ).forEach { sym ->
                app.ring.log("ROT  ${sym.code}")
                app.engine.feed(sym, InputSource.ROTARY)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ------------------------------------------------------- platform actions

    private fun requestBluetoothPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isEmpty()) return
        runCatching { btPermissionLauncher.launch(perms.toTypedArray()) }
    }

    private fun launchDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        }
        runCatching { discoverableLauncher.launch(intent) }
            .onFailure {
                app.ring.log("BT  discoverable intent failed: ${it.message}; granting permissions")
                requestBluetoothPermissions()
            }
    }

    private companion object {
        const val LONG_PRESS_MS = 600L
    }
}
