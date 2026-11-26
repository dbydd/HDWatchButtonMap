package dev.hdwatch.buttonmap.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAndRotaryTest {

    @Test
    fun detectsSamsungClassicBezelVsPixelAndTicWatchCrowns() {
        val gw6c = WatchDefaults.detect(manufacturer = "samsung", model = "SM-R960", hardware = "s5e5515")
        assertTrue(gw6c.invertRotation)
        assertEquals(WatchDefaults.RotaryKind.BEZEL, gw6c.rotaryKind)
        assertEquals(3, gw6c.rotateThreshold)

        val pixel3 = WatchDefaults.detect(manufacturer = "Google", model = "Pixel Watch 3", hardware = "eos")
        assertFalse(pixel3.invertRotation)
        assertEquals(WatchDefaults.RotaryKind.CROWN, pixel3.rotaryKind)
        assertEquals(2.0f, pixel3.rotaryScale, 0.001f)

        val tic = WatchDefaults.detect(manufacturer = "Mobvoi", model = "TicWatch Pro 5", hardware = "qcom")
        assertFalse(tic.invertRotation)
        assertEquals(WatchDefaults.RotaryKind.CROWN, tic.rotaryKind)

        val emu = WatchDefaults.detect(manufacturer = "Google", model = "sdk_gwear_x86_64", hardware = "ranchu")
        assertFalse(emu.invertRotation)
    }

    @Test
    fun rotaryAccumulatorSupportsFractionalCrownScale() {
        var now = 1000L
        val acc = RotaryAccumulator(clock = { now })

        // Pixel Watch fine-grained crown emits 0.5 per micro-step; with scale 2.0
        // and threshold 2, two micro-steps produce one CW click.
        assertEquals(emptyList<Symbol>(), acc.feedDelta(0.5f, threshold = 2, invert = false, scale = 2.0f))
        now += 40
        assertEquals(listOf(Symbol.CROWN_CW), acc.feedDelta(0.5f, threshold = 2, invert = false, scale = 2.0f))
    }

    @Test
    fun rotaryAccumulatorClearsRemainderOnDirectionReversalAndIdlePause() {
        var now = 1000L
        val acc = RotaryAccumulator(clock = { now })

        // Accumulate 2 toward threshold 3, then reverse immediately:
        assertEquals(emptyList<Symbol>(), acc.feedDelta(2f, threshold = 3, invert = false))
        now += 30
        // Reversal should not have to pay back +2 before going negative:
        assertEquals(listOf(Symbol.CROWN_CCW), acc.feedDelta(-3f, threshold = 3, invert = false))

        // Accumulate 2, pause longer than IDLE_RESET_MS, then tap +1 -> must NOT fire phantom tick.
        now += 30
        assertEquals(emptyList<Symbol>(), acc.feedDelta(2f, threshold = 3, invert = false))
        now += RotaryAccumulator.IDLE_RESET_MS + 100
        assertEquals(emptyList<Symbol>(), acc.feedDelta(1f, threshold = 3, invert = false))
    }
}
