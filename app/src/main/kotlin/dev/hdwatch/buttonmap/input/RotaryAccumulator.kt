package dev.hdwatch.buttonmap.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Turns raw rotary deltas (digital crowns on Pixel Watch / TicWatch / OnePlus /
 * emulator, physical bezels on Galaxy Watch Classic, touch bezels on standard
 * Galaxy Watch — all surfacing as AXIS_SCROLL / VSCROLL / HSCROLL) into clean
 * CW/CCW symbols.
 *
 * Includes:
 *  - [scale] multiplier so fine-grained digital crowns (e.g. Pixel Watch
 *    emitting fractional deltas) match physical clicky bezels.
 *  - Idle decay ([IDLE_RESET_MS]): leftover sub-threshold accumulation clears
 *    after a short pause so a future spin never fires a phantom extra tick.
 */
class RotaryAccumulator(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {

    private var accumulated = 0f
    private var lastEventMs = 0L

    /**
     * Feed one Android [MotionEvent]. Returns the symbols produced (normally
     * zero or one, rarely more after a fast fling).
     */
    fun feed(
        event: MotionEvent,
        threshold: Int,
        invert: Boolean,
        scale: Float = 1f,
    ): List<Symbol> = feedDelta(rotaryDelta(event), threshold, invert, scale)

    /** Pure-Kotlin entry point (directly unit-testable). */
    fun feedDelta(
        delta: Float,
        threshold: Int,
        invert: Boolean,
        scale: Float = 1f,
    ): List<Symbol> {
        if (delta == 0f) return emptyList()
        val now = clock()
        if (lastEventMs != 0L && now - lastEventMs > IDLE_RESET_MS) {
            accumulated = 0f
        }
        lastEventMs = now

        val clampedScale = scale.coerceIn(0.25f, 8f)
        val signed = (if (invert) -delta else delta) * clampedScale
        // Direction reversal clears stale remainder in the opposite direction
        // so reversing the crown responds immediately instead of fighting debt.
        if (accumulated != 0f && (accumulated > 0f) != (signed > 0f)) {
            accumulated = 0f
        }
        accumulated += signed

        val out = mutableListOf<Symbol>()
        val step = threshold.toFloat().coerceAtLeast(1f)
        while (abs(accumulated) >= step) {
            if (accumulated > 0f) {
                out += Symbol.CROWN_CW
                accumulated -= step
            } else {
                out += Symbol.CROWN_CCW
                accumulated += step
            }
        }
        return out
    }

    fun reset() {
        accumulated = 0f
        lastEventMs = 0L
    }

    companion object {
        /** Pause after which sub-threshold rotary remainder decays to zero. */
        const val IDLE_RESET_MS = 450L

        /** Extract the rotary delta from a generic-motion event, any axis OEM used. */
        fun rotaryDelta(event: MotionEvent): Float {
            if (event.source and InputDevice.SOURCE_ROTARY_ENCODER != 0) {
                event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f }?.let { return it }
            }
            // Fallbacks seen on some OEM builds that route bezel/crown input differently.
            event.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f }?.let { return it }
            event.getAxisValue(MotionEvent.AXIS_HSCROLL).takeIf { it != 0f }?.let { return it }
            return 0f
        }
    }
}
