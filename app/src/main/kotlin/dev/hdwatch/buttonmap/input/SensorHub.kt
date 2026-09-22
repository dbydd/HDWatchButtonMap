package dev.hdwatch.buttonmap.input

import android.app.Application
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ConfigRepository
import dev.hdwatch.buttonmap.engine.InputSource
import dev.hdwatch.buttonmap.engine.SequenceEngine
import dev.hdwatch.buttonmap.hid.ReportRing
import java.lang.reflect.Modifier

/** Probing gesture backend: probed sensor scan / @hide types / IMU shake → bindable capabilities. */
class SensorHub(
    private val app: Application,
    private val repo: ConfigRepository,
    private val engine: SequenceEngine,
    private val ring: ReportRing,
) : GestureBackend {

    private val sensorManager = app.getSystemService(SensorManager::class.java)

    /** One probed gesture source plus the sensor it listens on. */
    private class Entry(
        /** Map key inside Settings.sensorToSymbol: Sensor.name, or "imu:shake". */
        val id: String,
        val label: String,
        /** Sensor.name for demotion checks ("native shake owns it"); "" for imu. */
        val sensorName: String,
        val sensor: Sensor,
        val rate: Int,
        val shake: Boolean,
        val permission: String?,
        /** False → BROKEN (registration failed, no hardware, superseded). */
        @Volatile var hardwareOk: Boolean,
        val detail: String,
    )

    /** Cached probe result; recomputed by refresh(). */
    @Volatile private var entries: List<Entry> = emptyList()

    init {
        refresh()
    }

    // ------------------------------------------------------------- backend api

    override fun capabilities(): List<GestureCapability> = entries.map { entry ->
        val status = when {
            !gateEnabled -> CapStatus.GATED
            entry.permission != null &&
                app.checkSelfPermission(entry.permission) != PackageManager.PERMISSION_GRANTED ->
                CapStatus.NEEDS_PERMISSION
            !entry.hardwareOk -> CapStatus.BROKEN
            else -> CapStatus.AVAILABLE
        }
        GestureCapability(
            id = entry.id,
            label = entry.label,
            status = status,
            // Hardware/status info only; the settings row renders the binding itself.
            detail = entry.detail,
        )
    }

    override fun refresh() {
        val manager = sensorManager
        if (manager == null) {
            entries = emptyList()
            return
        }
        val out = mutableListOf<Entry>()
        val seenTypes = HashSet<Int>()

        fun offer(sensor: Sensor, shake: Boolean) {
            val type = sensor.type
            // The emulator's goldfish sensors HAL aborts on @hide gesture
            // activations (type 20..29 verified crash). Real hardware may
            // support them, so the guard is emulator-only.
            if (isEmulator && type in EMULATOR_UNSAFE_TYPES) return
            if (!seenTypes.add(type)) return
            val permission = runCatching {
                sensor.javaClass.getMethod("getRequiredPermission").invoke(sensor) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
            out += Entry(
                id = sensor.name,
                label = friendlyLabel(sensor),
                sensorName = sensor.name,
                sensor = sensor,
                rate = if (shake) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_NORMAL,
                shake = shake,
                permission = permission,
                hardwareOk = probe(manager, sensor),
                detail = app.getString(R.string.sns_detail_type, type, sensor.name),
            )
        }

        // 1. every sensor the HAL reports, kept when name or type smells gestural
        runCatching { manager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
            .filter { it.isGestureFlavored() }
            .forEach { offer(it, shake = false) }

        // 2. public TYPE_* constants, merged in and deduped by type
        Sensor::class.java.fields.forEach { field ->
            if (!field.isPublicIntStatic) return@forEach
            if (REFLECT_HINTS.none { field.name.contains(it) }) return@forEach
            val type = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
            val sensor = runCatching { manager.getDefaultSensor(type) }.getOrNull() ?: return@forEach
            offer(sensor, shake = false)
        }

        // 3. self-built shake; superseded when the HAL already exposes one
        val linear = runCatching { manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) }.getOrNull()
        if (linear != null) {
            val native = out.any { it.sensorName.uppercase().contains("SHAKE") }
            out += Entry(
                id = IMU_SHAKE_ID,
                label = app.getString(R.string.sns_cap_shake),
                sensorName = "",
                sensor = linear,
                rate = SensorManager.SENSOR_DELAY_GAME,
                shake = true,
                permission = null,
                hardwareOk = !native && probe(manager, linear),
                detail = if (native) {
                    app.getString(R.string.sns_detail_native_shake)
                } else {
                    app.getString(R.string.sns_detail_imu_shake)
                },
            )
        }

        entries = out
        ring.log("SNS  probed ${out.size} capability(ies): " +
            out.joinToString(", ") { "${it.label}/${it.id}" })
    }

    override fun bind(capabilityId: String, symbol: Symbol?) {
        if (entries.none { it.id == capabilityId }) {
            ring.log("SNS  bind ignored unknown capability '$capabilityId'")
            return
        }
        // "" is an explicit unbind; null arrives as the same empty value.
        val code = symbol?.code ?: ""
        repo.updateSettings { it.copy(sensorToSymbol = it.sensorToSymbol + (capabilityId to code)) }
        ring.log("SNS  bind $capabilityId -> ${code.ifEmpty { "无" }}")
    }

    // ------------------------------------------------------------- listening

    @Volatile private var listening = false
    private val listeners = mutableListOf<Pair<Sensor, SensorEventListener>>()
    private val shakeDetector = ImuShakeDetector {
        val symbol = boundSymbol(IMU_SHAKE_ID) ?: return@ImuShakeDetector
        fire(IMU_SHAKE_ID, symbol)
    }

    override fun start() {
        if (listening) return
        if (!gateEnabled) {
            ring.log("SNS  监听未启用（设置中打开 gestureSensorsEnabled）")
            return
        }
        val manager = sensorManager ?: return
        entries.forEach { entry ->
            val status = statusOf(entry)
            if (status == CapStatus.BROKEN) {
                ring.log("SNS  skip broken ${entry.label}")
                return@forEach
            }
            if (status != CapStatus.AVAILABLE) return@forEach
            val listener: SensorEventListener = if (entry.shake) {
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) = shakeDetector.onSensorChanged(event)
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
                }
            } else {
                object : SensorEventListener {
                    private var lastFire = 0L
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.isEmpty() || event.values[0] < 0.5f) return
                        val now = System.currentTimeMillis()
                        if (now - lastFire < DEBOUNCE_MS) return
                        lastFire = now
                        val symbol = boundSymbol(entry.id) ?: return
                        fire(entry.id, symbol)
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
                }
            }
            val ok = runCatching {
                manager.registerListener(listener, entry.sensor, entry.rate)
            }.getOrDefault(false)
            if (ok) listeners += entry.sensor to listener
            else ring.log("SNS  cannot register ${entry.label}")
        }
        listening = listeners.isNotEmpty()
        ring.log("SNS  start: ${listeners.size} capability(ies)")
    }

    override fun stop() {
        if (!listening) return
        val manager = sensorManager
        listeners.forEach { (sensor, listener) ->
            runCatching { manager?.unregisterListener(listener, sensor) }
        }
        listeners.clear()
        listening = false
        ring.log("SNS  stop")
    }

    /** Short-lived registration probe: must never crash and never stay resident. */
    private fun probe(manager: SensorManager, sensor: Sensor): Boolean {
        val noop = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = Unit
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val ok = runCatching {
            manager.registerListener(noop, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        runCatching { manager.unregisterListener(noop) }
        return ok
    }

    // --------------------------------------------------------------- internals

    private fun statusOf(entry: Entry): CapStatus = when {
        !gateEnabled -> CapStatus.GATED
        entry.permission != null &&
            app.checkSelfPermission(entry.permission) != PackageManager.PERMISSION_GRANTED ->
            CapStatus.NEEDS_PERMISSION
        !entry.hardwareOk -> CapStatus.BROKEN
        else -> CapStatus.AVAILABLE
    }

    /** Raw map value → Symbol; blank or unknown code → null (explicit unbind). */
    private fun boundSymbol(capabilityId: String): Symbol? {
        val raw = repo.config.value.settings.sensorToSymbol[capabilityId] ?: return null
        val code = raw.trim()
        if (code.isEmpty()) return null
        return Symbol.fromCode(code)
    }

    private fun fire(capabilityId: String, symbol: Symbol) {
        val label = entries.firstOrNull { it.id == capabilityId }?.label ?: capabilityId
        ring.log("SNS  $label -> ${symbol.code}")
        // Tag the origin: a KeyDown bound to a sensor latches instead of tapping.
        engine.feed(symbol, InputSource.SENSOR)
    }

    private val gateEnabled: Boolean
        get() = repo.config.value.settings.gestureSensorsEnabled

    private fun Sensor.isGestureFlavored(): Boolean {
        val haystack = "${this.name} ${stringType ?: ""}".uppercase()
        return SCAN_HINTS.any { haystack.contains(it) }
    }

    private fun friendlyLabel(sensor: Sensor): String {
        val haystack = "${sensor.name} ${sensor.stringType ?: ""}".uppercase()
        FRIENDLY.forEach { (key, labelRes) -> if (haystack.contains(key)) return app.getString(labelRes) }
        return sensor.name.take(20)
    }

    private val java.lang.reflect.Field.isPublicIntStatic: Boolean
        get() = Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) &&
            type == Int::class.javaPrimitiveType

    private companion object {
        const val DEBOUNCE_MS = 800L
        const val IMU_SHAKE_ID = "imu:shake"
        val EMULATOR_UNSAFE_TYPES = 20..29
        val SCAN_HINTS = listOf(
            "TILT", "WRIST", "PICK", "GLANCE", "DOUBLE_TAP", "SHAKE", "FLIP", "GRIP", "PALM", "GESTURE",
        )
        val REFLECT_HINTS = listOf("GESTURE", "TILT", "WRIST", "DOUBLE_TAP", "PICK_UP", "GLANCE", "TAP_")
        val FRIENDLY = listOf(
            "WRIST" to R.string.sns_cap_wrist,
            "DOUBLE_TAP" to R.string.sns_cap_double_tap,
            "SHAKE" to R.string.sns_cap_shake,
            "FLIP" to R.string.sns_cap_flip,
            "GRIP" to R.string.sns_cap_grip,
            "PALM" to R.string.sns_cap_palm,
            "PICK" to R.string.sns_cap_pick,
            "GLANCE" to R.string.sns_cap_glance,
            "TILT" to R.string.sns_cap_tilt,
            "GESTURE" to R.string.sns_cap_gesture,
        )
        val isEmulator: Boolean
            get() = Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
                Build.FINGERPRINT.contains("generic")
    }
}
