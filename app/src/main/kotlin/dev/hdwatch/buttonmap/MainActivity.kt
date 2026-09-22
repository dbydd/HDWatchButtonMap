package dev.hdwatch.buttonmap

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.hdwatch.buttonmap.input.Symbol
import dev.hdwatch.buttonmap.ui.HDTheme
import dev.hdwatch.buttonmap.ui.HidScreen
import dev.hdwatch.buttonmap.ui.LocalAppNav
import dev.hdwatch.buttonmap.ui.LocalPlatformActions
import dev.hdwatch.buttonmap.ui.LogScreen
import dev.hdwatch.buttonmap.ui.MappingEditScreen
import dev.hdwatch.buttonmap.ui.MappingListScreen
import dev.hdwatch.buttonmap.ui.MacroListScreen
import dev.hdwatch.buttonmap.ui.MenuScreen
import dev.hdwatch.buttonmap.ui.PadScreen
import dev.hdwatch.buttonmap.ui.PlatformActions
import dev.hdwatch.buttonmap.ui.Route
import dev.hdwatch.buttonmap.ui.SettingsScreen
import dev.hdwatch.buttonmap.ui.AppNav

/**
 * Single activity: routes raw input (keys, rotary) into the sequence engine
 * while on the pad screen, and hands platform capabilities (SAF, BT intents,
 * permission prompts) down through CompositionLocals.
 */
class MainActivity : ComponentActivity() {

    private val app get() = HdApp.instance
    private val nav = AppNav()
    private val rotary = dev.hdwatch.buttonmap.input.RotaryAccumulator()

    private var stemDownAt = 0L

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
            HDTheme {
                CompositionLocalProvider(
                    LocalAppNav provides nav,
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

        // Off the pad, the stem/home key means "back".
        if (nav.current != Route.Pad) {
            if (isStemKey(event.keyCode) && event.action == KeyEvent.ACTION_UP) {
                nav.pop()
                return true
            }
            maybeLogUnknownKey(event, settings.captureUnknownInput)
            return super.dispatchKeyEvent(event)
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (isStemKey(event.keyCode)) {
            if (isDown && event.repeatCount == 0) {
                stemDownAt = event.eventTime
            } else if (!isDown) {
                val held = event.eventTime - stemDownAt
                app.engine.feed(if (held >= LONG_PRESS_MS) Symbol.STEM_LONG else Symbol.STEM)
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

    private fun isStemKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_STEM_1 || code == KeyEvent.KEYCODE_STEM_2 ||
            code == KeyEvent.KEYCODE_STEM_3

    // ---------------------------------------------------------- rotary input

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val deltaRaw = dev.hdwatch.buttonmap.input.RotaryAccumulator.rotaryDelta(event)
        if (app.configRepo.config.value.settings.captureUnknownInput && deltaRaw != 0f) {
            app.ring.log(
                "GEN  act=${android.view.MotionEvent.actionToString(event.action)} " +
                    "src=0x${Integer.toHexString(event.source)} " +
                    "scroll=${event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)} " +
                    "v=${event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)} " +
                    "h=${event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)}",
            )
        }
        if (nav.current == Route.Pad) {
            val delta = deltaRaw
            if (delta != 0f) {
                val s = app.configRepo.config.value.settings
                val cfg = app.configRepo.config.value
                rotary.feed(event, cfg.effectiveRotateThreshold(), s.invertRotation).forEach { sym ->
                    app.ring.log("ROT  ${sym.code}")
                    app.engine.feed(sym, dev.hdwatch.buttonmap.engine.InputSource.ROTARY)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ------------------------------------------------------- platform actions

    private fun requestBluetoothPermissions() {
        runCatching {
            btPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE,
                    android.Manifest.permission.BLUETOOTH_SCAN,
                ),
            )
        }
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
