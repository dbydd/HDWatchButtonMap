package dev.hdwatch.buttonmap.input

import android.view.MotionEvent
import android.view.InputDevice
import kotlin.math.abs

/**
 * Turns raw rotary deltas (crown on the emulator, physical bezel on the
 * Galaxy Watch Classic line — both surface as AXIS_SCROLL from
 * SOURCE_ROTARY_ENCODER) into whole CW/CCW symbols.
 */
class RotaryAccumulator {

    private var accumulated = 0f

    /**
     * Feed one motion event. Returns the symbols produced (normally zero or
     * one, rarely more after a fast fling).
     */
    fun feed(event: MotionEvent, threshold: Int, invert: Boolean): List<Symbol> {
        val delta = rotaryDelta(event)
        if (delta == 0f) return emptyList()
        val signed = if (invert) -delta else delta
        accumulated += signed
        val out = mutableListOf<Symbol>()
        val step = threshold.toFloat().coerceAtLeast(1f)
        while (abs(accumulated) >= step) {
            if (accumulated > 0) out += Symbol.CROWN_CW else out += Symbol.CROWN_CCW
            accumulated -= if (accumulated > 0) step else -step
        }
        return out
    }

    fun reset() {
        accumulated = 0f
    }

    companion object {
        /** Extract the rotary delta from a generic-motion event, any axis OEM used. */
        fun rotaryDelta(event: MotionEvent): Float {
            if (event.source and InputDevice.SOURCE_ROTARY_ENCODER != 0) {
                event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f }?.let { return it }
            }
            // Fallbacks seen on some builds that route bezel input differently.
            event.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f }?.let { return it }
            event.getAxisValue(MotionEvent.AXIS_HSCROLL).takeIf { it != 0f }?.let { return it }
            return 0f
        }
    }
}
