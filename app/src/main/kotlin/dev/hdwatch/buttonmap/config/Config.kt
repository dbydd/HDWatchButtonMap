package dev.hdwatch.buttonmap.config

import dev.hdwatch.buttonmap.input.Symbol

/** Keyboard modifier byte bits (left-side variants; HID usage 0xE0..0xE7 map 1:1). */
enum class KeyMod(val bit: Int) {
    CTRL(0x01),
    SHIFT(0x02),
    ALT(0x04),
    GUI(0x08),
}

/**
 * One atomic HID action. Macros are flat step lists; single mappings hold one step.
 * Key names resolve through dev.hdwatch.buttonmap.hid.KeyTable.
 */
sealed interface Step {
    /** Full tap: press key(+mods), release immediately after a short gap. */
    data class TapKey(val key: String, val mods: Set<KeyMod> = emptySet()) : Step

    /** Hold semantics for movement macros (W held, later released). */
    data class KeyDown(val key: String, val mods: Set<KeyMod> = emptySet()) : Step
    data class KeyUp(val key: String, val mods: Set<KeyMod> = emptySet()) : Step

    /** Type a string: per-char key taps with [Settings.textDelayMs] gap. */
    data class TypeText(val text: String) : Step

    /** Idle wait in milliseconds. */
    data class Wait(val ms: Long) : Step

    /**
     * One absolute mouse report: held [buttons] ("left"/"right"/"middle"/
     * "back"/"forward"), relative [dx]/[dy] movement, [wheel] notches.
     */
    data class Mouse(
        val buttons: Set<String> = emptySet(),
        val dx: Int = 0,
        val dy: Int = 0,
        val wheel: Int = 0,
    ) : Step

    /** Convenience: press + release one mouse button [count] times. */
    data class MouseClick(val button: String = "left", val count: Int = 1) : Step

    /** All mouse buttons up. */
    data object MouseRelease : Step

    /** Consumer page key by name ("PLAY_PAUSE", "MUTE", ...). */
    data class ConsumerKey(val usage: String) : Step
}

data class Macro(
    val id: String,
    val name: String,
    val sequence: List<Symbol>,
    val steps: List<Step>,
    val enabled: Boolean = true,
    val repeat: Int = 1,
) {
    val sequenceDisplay: String
        get() = sequence.joinToString(" ") { it.display }
}

data class Settings(
    /**
     * Accumulated rotary-axis delta that produces one CW/CCW symbol.
     * Galaxy Watch 6 Classic bezel emits 1.0 per detent (measured); 3 detents
     * feel deliberate on the wrist (user-calibrated).
     */
    val rotateThreshold: Int = 3,
    /** GW6C sends clockwise as negative; invert maps it back to CROWN_CW. */
    val invertRotation: Boolean = true,
    /** Idle time that drops a half-typed sequence with no penalty. */
    val sequenceTimeoutMs: Long = 3000,
    /** Per-character gap when typing text; also the down gap for single taps. */
    val textDelayMs: Long = 40,
    /** Debug aid: show every raw captured event on the input-log screen. */
    val captureUnknownInput: Boolean = true,
    /** Force the log-only transport even when Bluetooth HID is available. */
    val forceLoggingTransport: Boolean = false,
    /**
     * Gesture-sensor listening is opt-in: some builds (emulator goldfish HAL)
     * abort the whole sensors service when @hide gesture types are activated.
     */
    val gestureSensorsEnabled: Boolean = false,
    val sensorToSymbol: Map<String, String> = emptyMap(),
)

data class Config(
    val version: Int = CURRENT_VERSION,
    val settings: Settings = Settings(),
    /** Immediate actions for symbols that are not mid-sequence. */
    val single: Map<Symbol, Step> = emptyMap(),
    val macros: List<Macro> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
        val EMPTY = Config()
    }
}
