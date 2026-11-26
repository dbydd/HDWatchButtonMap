package dev.hdwatch.buttonmap.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Hardware-aware defaults & API-level compatibility helpers across Wear OS 3–6
 * and full-Android smartwatches (Galaxy Watch Classic/Standard/Ultra,
 * Pixel Watch 1/2/3, OnePlus Watch 2/3, TicWatch Pro 5, Xiaomi Watch, Oppo Watch).
 */
object WatchDefaults {

    enum class RotaryKind { BEZEL, CROWN, TOUCH_ONLY }

    data class HardwareProfile(
        val label: String,
        val rotaryKind: RotaryKind,
        val invertRotation: Boolean,
        val rotateThreshold: Int,
        val rotaryScale: Float,
    )

    /** Pure-Kotlin classifier so it can be unit-tested without Android Build stubs. */
    fun detect(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        hardware: String = Build.HARDWARE.orEmpty(),
    ): HardwareProfile {
        val mfr = manufacturer.trim().lowercase()
        val mdl = model.trim().uppercase()
        val hw = hardware.trim().lowercase()

        val displayModel = model.trim().ifBlank { manufacturer.trim().ifBlank { "Watch" } }

        // Emulator (Wear AVD): digital rotary slider emits positive CW deltas of ~1.0.
        if (hw.contains("goldfish") || hw.contains("ranchu") || mdl.contains("SDK_GWEAR")) {
            return HardwareProfile(
                label = "$displayModel · Sim",
                rotaryKind = RotaryKind.CROWN,
                invertRotation = false,
                rotateThreshold = 2,
                rotaryScale = 1.0f,
            )
        }

        // Samsung Galaxy Watch family:
        // Classic models (SM-R88x/R89x GW4C, SM-R95x/R96x GW6C) have physical
        // rotating bezels that emit negative AXIS_SCROLL on clockwise turns.
        // Non-Classic / Ultra models use a capacitive touch bezel or crown-like
        // dial that also reports inverted scroll on One UI Watch.
        if (mfr.contains("samsung")) {
            val isClassicBezel = mdl.startsWith("SM-R88") || mdl.startsWith("SM-R89") ||
                mdl.startsWith("SM-R95") || mdl.startsWith("SM-R96") ||
                mdl.contains("CLASSIC")
            return HardwareProfile(
                label = if (isClassicBezel) "$displayModel · Bezel" else "$displayModel · Dial",
                rotaryKind = if (isClassicBezel) RotaryKind.BEZEL else RotaryKind.TOUCH_ONLY,
                invertRotation = true,
                rotateThreshold = if (isClassicBezel) 3 else 2,
                rotaryScale = 1.0f,
            )
        }

        // Google Pixel Watch 1/2/3: smooth digital crown emits fine-grained
        // positive CW deltas (often 0.2..0.5 per click); 2.0x scale normalizes feel.
        if (mfr.contains("google") || mdl.contains("PIXEL WATCH")) {
            return HardwareProfile(
                label = "$displayModel · Crown",
                rotaryKind = RotaryKind.CROWN,
                invertRotation = false,
                rotateThreshold = 2,
                rotaryScale = 2.0f,
            )
        }

        // OnePlus / Oppo / Mobvoi TicWatch / Xiaomi / Fossil / Garmin-Wear:
        // standard AOSP positive-CW digital crown.
        if (mfr.contains("oneplus") || mfr.contains("oppo") || mfr.contains("mobvoi") ||
            mfr.contains("xiaomi") || mfr.contains("fossil")
        ) {
            return HardwareProfile(
                label = "$displayModel · Crown",
                rotaryKind = RotaryKind.CROWN,
                invertRotation = false,
                rotateThreshold = 2,
                rotaryScale = 1.5f,
            )
        }

        return HardwareProfile(
            label = displayModel,
            rotaryKind = RotaryKind.CROWN,
            invertRotation = false,
            rotateThreshold = 2,
            rotaryScale = 1.0f,
        )
    }

    /** API 30–36 safe vibrator lookup. */
    @Suppress("DEPRECATION")
    fun defaultVibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    /** API 30 uses install-time BLUETOOTH; API 31+ requires runtime BLUETOOTH_CONNECT. */
    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
