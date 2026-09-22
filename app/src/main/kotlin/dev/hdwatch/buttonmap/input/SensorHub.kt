package dev.hdwatch.buttonmap.input

import android.app.Application
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.hdwatch.buttonmap.config.ConfigRepository
import dev.hdwatch.buttonmap.engine.SequenceEngine
import dev.hdwatch.buttonmap.hid.ReportRing
import java.lang.reflect.Modifier

/**
 * Gesture-sensor front door.
 *
 * Android's *usable* gesture surface on watches is the ambient sensor family
 * (tilt detector / pick-up / glance / double-tap / wrist-off …). Which of them
 * exist is OEM-dependent, so instead of a hardcoded list we reflect over every
 * public `Sensor.TYPE_*` constant, ask SensorManager whether the device has
 * one, and keep the ones whose name smells like a gesture. Each matched
 * sensor is offered to the config as `sensorToSymbol[sensorName] = "G1".."G4"`;
 * unmapped candidates still show up on the settings screen so the user can
 * assign them.
 */
class SensorHub(
    app: Application,
    private val repo: ConfigRepository,
    private val engine: SequenceEngine,
    private val ring: ReportRing,
) {

    private val sensorManager = app.getSystemService(SensorManager::class.java)

    data class Candidate(val sensor: Sensor, val type: Int)

    /** Gesture-flavored public sensor types present on this device. */
    fun candidates(): List<Candidate> {
        val manager = sensorManager ?: return emptyList()
        val seenTypes = HashSet<Int>()
        val out = mutableListOf<Candidate>()
        GESTURE_NAME_HINTS.forEach { hint ->
            Sensor::class.java.fields
                .filter { it.modifierHasIntType() && it.name.startsWith("TYPE_") && it.name.contains(hint) }
                .forEach { field ->
                    val type = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
                    if (!seenTypes.add(type)) return@forEach
                    // The emulator's goldfish sensors HAL aborts on @hide gesture
                    // activations (type 22..27 verified crash). Real hardware may
                    // support them, so the guard is emulator-only.
                    if (isEmulator && type in EMULATOR_UNSAFE_TYPES) return@forEach
                    manager.getSensorList(type).firstOrNull()?.let { out += Candidate(it, type) }
                }
        }
        return out
    }

    /** Symbol a sensor fires: config override first, else positional fallback. */
    fun symbolFor(candidateIndex: Int, sensor: Sensor): Symbol? {
        val mapped = repo.config.value.settings.sensorToSymbol[sensor.name]
            ?.let { Symbol.fromCode(it) }
        if (mapped != null) return mapped
        if (repo.config.value.settings.sensorToSymbol.containsKey(sensor.name)) return null // explicitly unbound
        return FALLBACK[candidateIndex % FALLBACK.size]
    }

    fun describeAvailable(): String =
        candidates().joinToString("; ") { "${it.sensor.name} (#${it.type})" }
            .ifBlank { "本机无可用手势传感器" }

    // ------------------------------------------------------------ listening

    @Volatile private var listening = false
    private val listeners = mutableListOf<Pair<Sensor, SensorEventListener>>()
    private val lastFire = HashMap<String, Long>()

    fun start() {
        if (listening) return
        if (!repo.config.value.settings.gestureSensorsEnabled) {
            ring.log("SNS  监听未启用（设置中打开 gestureSensorsEnabled）")
            return
        }
        val manager = sensorManager ?: return
        candidates().forEachIndexed { index, cand ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.values.isEmpty() || event.values[0] < 0.5f) return
                    val now = System.currentTimeMillis()
                    val key = cand.sensor.name
                    val prev = lastFire[key] ?: 0L
                    if (now - prev < DEBOUNCE_MS) return
                    lastFire[key] = now
                    val sym = symbolFor(index, cand.sensor) ?: return
                    ring.log("SNS  ${cand.sensor.name} -> ${sym.code}")
                    engine.feed(sym)
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            val ok = runCatching {
                manager.registerListener(listener, cand.sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }.getOrDefault(false)
            if (ok) listeners += cand.sensor to listener
            else ring.log("SNS  cannot register ${cand.sensor.name}")
        }
        listening = listeners.isNotEmpty()
        ring.log("SNS  start: ${listeners.size} sensor(s)")
    }

    fun stop() {
        if (!listening) return
        val manager = sensorManager
        listeners.forEach { (sensor, listener) ->
            runCatching { manager?.unregisterListener(listener, sensor) }
        }
        listeners.clear()
        listening = false
        ring.log("SNS  stop")
    }

    private companion object {
        const val DEBOUNCE_MS = 800L
        val GESTURE_NAME_HINTS = listOf("GESTURE", "TILT", "WRIST", "DOUBLE_TAP", "PICK_UP", "GLANCE", "TAP_")
        val EMULATOR_UNSAFE_TYPES = 20..29
        val FALLBACK = listOf(
            Symbol.GESTURE_1, Symbol.GESTURE_2, Symbol.GESTURE_3, Symbol.GESTURE_4,
        )
        val isEmulator: Boolean
            get() = Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
                Build.FINGERPRINT.contains("generic")

        private fun java.lang.reflect.Field.modifierHasIntType(): Boolean =
            Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && type == Int::class.javaPrimitiveType
    }
}
