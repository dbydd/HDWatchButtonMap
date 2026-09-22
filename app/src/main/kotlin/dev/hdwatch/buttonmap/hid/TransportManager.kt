package dev.hdwatch.buttonmap.hid

import android.app.Application
import dev.hdwatch.buttonmap.config.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two transports and hands callers the one that matches settings:
 * Bluetooth by default, logging when forced or when no adapter exists.
 */
class TransportManager(
    private val app: Application,
    val ring: ReportRing,
) {
    private var bluetooth: BluetoothHidTransport? = null
    private val logging = LoggingTransport(ring)

    private val _active = MutableStateFlow<HidTransport>(logging)
    val active: StateFlow<HidTransport> = _active.asStateFlow()

    fun forSettings(settings: Settings): HidTransport {
        val next = if (settings.forceLoggingTransport) {
            logging
        } else {
            bluetoothInstance() ?: logging
        }
        if (_active.value !== next) _active.value = next
        return next
    }

    private fun bluetoothInstance(): BluetoothHidTransport? {
        bluetooth?.let { return it }
        val created = BluetoothHidTransport(app, ring)
        // Keep the instance only when the adapter/profile route is plausible;
        // a dead adapter would otherwise spam registration attempts.
        return if (created.status.value.detail.contains("无蓝牙适配器")) {
            created.close()
            null
        } else {
            created.also { bluetooth = it }
        }
    }

    /** Create-if-absent; the HID status screen wants real BT state even while logging mode is on. */
    fun bluetoothOrNull(): BluetoothHidTransport? = bluetoothInstance()

    fun shutdown() {
        bluetooth?.close()
        bluetooth = null
    }
}
