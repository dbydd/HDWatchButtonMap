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

    /**
     * Latch a key: the first press puts it down and leaves it there, the next
     * press lifts it. Bound to a dial slot this is how CTRL is held while a
     * stratagem code is typed; the engine also drops it when a sequence lands.
     */
    data class Hold(val key: String, val mods: Set<KeyMod> = emptySet()) : Step

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

/** What an input symbol does the moment it arrives in this profile. */
enum class ProfileKind {
    /** Every mapped symbol fires immediately; sequences disabled. */
    KEYS,

    /** Helldivers-style: symbols buffer into sequences and fire macros. */
    MACRO,
}

/**
 * A complete control surface: its own symbol→action map plus macro set.
 * Exactly one profile is active; the pad center card cycles between them.
 */
data class Profile(
    val id: String,
    val name: String,
    val kind: ProfileKind,
    val single: Map<Symbol, Step> = emptyMap(),
    val macros: List<Macro> = emptyList(),
    /** Overrides Settings.rotateThreshold when set (per-profile feel). */
    val rotateThreshold: Int? = null,
    /**
     * Dial slots (keyed by symbol code: "CW","CCW","S","SL","G1".."G4") that
     * execute a step directly instead of feeding the sequence engine. A
     * KeyDown step turns the slot into a hold button — how CTRL is held while
     * the arrow code is typed.
     */
    val ringSteps: Map<String, Step> = emptyMap(),
)

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
    /**
     * Repeat window for a *latched* step driven by the crown: detents inside
     * it count as one toggle. It never applies to plain rotary mappings, so a
     * wheel-bound crown keeps firing on every detent. 0 disables the guard.
     */
    val rotaryLatchDebounceMs: Long = 700,
    /**
     * Upper bound of a random extra gap inserted between consecutive macro
     * key steps. Mechanical, perfectly even bursts look synthetic to game
     * input handlers; 0 disables the jitter.
     */
    val keyJitterMs: Long = 25,
    /** Debug aid: show every raw captured event on the input-log screen. */
    val captureUnknownInput: Boolean = true,
    /** Force the log-only transport even when Bluetooth HID is available. */
    val forceLoggingTransport: Boolean = false,
    /**
     * Gesture-sensor listening is opt-in: some builds (emulator goldfish HAL)
     * abort the whole sensors service when @hide gesture types are activated.
     */
    val gestureSensorsEnabled: Boolean = false,
    /** Keep HID registered through a foreground service while app is backgrounded. */
    val keepAliveService: Boolean = true,
    /** Master switch for every haptic pattern the app emits. */
    val vibrationEnabled: Boolean = true,
    val sensorToSymbol: Map<String, String> = emptyMap(),
)

data class Config(
    val version: Int = CURRENT_VERSION,
    val settings: Settings = Settings(),
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String = "",
) {
    val activeProfile: Profile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
            ?: Profile("default", "直控", ProfileKind.KEYS)

    fun withActive(id: String): Config =
        if (profiles.any { it.id == id }) copy(activeProfileId = id) else this

    /** Replaces the active profile via [transform]; used by all on-watch editors. */
    fun mapActive(transform: (Profile) -> Profile): Config =
        copy(profiles = profiles.map { if (it.id == activeProfile.id) transform(it) else it })

    fun effectiveRotateThreshold(): Int =
        activeProfile.rotateThreshold ?: settings.rotateThreshold

    companion object {
        const val CURRENT_VERSION = 2
        const val MIN_READABLE_VERSION = 1
        val EMPTY = Config()

        fun legacyDefault(): Config = Config(
            profiles = listOf(
                Profile("keys", "直控", ProfileKind.KEYS),
                Profile("macros", "宏", ProfileKind.MACRO),
            ),
            activeProfileId = "keys",
        )
    }
}
