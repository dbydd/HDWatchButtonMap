package dev.hdwatch.buttonmap.input

/** Runtime status of one detected gesture capability. */
enum class CapStatus {
    /** Ready: events will fire when listening is enabled. */
    AVAILABLE,

    /** Present but gated off (Settings gestureSensorsEnabled switch). */
    GATED,

    /** Exists yet needs a permission the app lacks (shown, not bindable). */
    NEEDS_PERMISSION,

    /** Probed and failed to register / device simply has nothing. */
    BROKEN,
}

/**
 * One bindable gesture source. `id` is stable across launches and is the key
 * inside Settings.sensorToSymbol: system sensors use
 * "sensor:<typeInt>:<vendor name>", self-built detectors use a plain key like
 * "imu:shake".
 */
data class GestureCapability(
    val id: String,
    val label: String,
    val status: CapStatus,
    val detail: String = "",
)

/**
 * Device-agnostic gesture layer. Every OEM exposes hand gestures differently
 * (public AOSP sensor types, @hide constants, vendor-named sensors, or
 * nothing) — implementations must *probe* rather than assume, expose what
 * works, and stay silent about the rest. Feeding a bound Symbol into the
 * sequence engine happens inside; UI only reads capabilities and binds.
 */
interface GestureBackend {
    /** Latest probe result; cheap, recomputed on refresh(). */
    fun capabilities(): List<GestureCapability>

    /** Re-probe sensors and permissions. Call when a screen opens. */
    fun refresh()

    /** Bind a capability to a symbol (null clears the binding). Persists. */
    fun bind(capabilityId: String, symbol: Symbol?)

    fun start()
    fun stop()
}
