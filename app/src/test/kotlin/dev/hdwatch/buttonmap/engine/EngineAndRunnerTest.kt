package dev.hdwatch.buttonmap.engine

import dev.hdwatch.buttonmap.config.Config
import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.Macro
import dev.hdwatch.buttonmap.config.Profile
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.Settings
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.hid.HidTransport
import dev.hdwatch.buttonmap.hid.ReportRing
import dev.hdwatch.buttonmap.hid.TransportKind
import dev.hdwatch.buttonmap.hid.TransportState
import dev.hdwatch.buttonmap.hid.TransportStatus
import dev.hdwatch.buttonmap.input.Symbol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineAndRunnerTest {

    private class FakeTransport(private val ring: ReportRing) : HidTransport {
        override val kind = TransportKind.LOGGING
        override val status: StateFlow<TransportStatus> = MutableStateFlow(
            TransportStatus(TransportKind.LOGGING, TransportState.LOGGING, "Fake"),
        )
        override val reportLog: SharedFlow<String> = ring.recent
        override val logHistory: List<String> get() = ring.history

        val kbdReports = mutableListOf<Pair<Int, List<Int>>>()
        val mouseReports = mutableListOf<List<Int>>()
        val consumerReports = mutableListOf<Int>()

        override suspend fun sendKeyboardReport(modifierBits: Int, keyUsages: List<Int>): Boolean {
            kbdReports += modifierBits to keyUsages
            return true
        }

        override suspend fun sendMouseReport(buttonBits: Int, dx: Int, dy: Int, wheel: Int): Boolean {
            mouseReports += listOf(buttonBits, dx, dy, wheel)
            return true
        }

        override suspend fun sendConsumerReport(usage: Int): Boolean {
            consumerReports += usage
            return true
        }
    }

    @Test
    fun holdCtrlSetsHidModifierByteAndEmergencyReleaseClearsLatchedState() = runTest {
        val ring = ReportRing()
        val fake = FakeTransport(ring)
        val settings = Settings(textDelayMs = 10, keyJitterMs = 0)
        val runner = MacroRunner(this, { fake }, { settings }, ring)

        // Latch CTRL via Step.Hold: must set modifier bit 0x01 with empty usages array.
        runner.runStep(Step.Hold("CTRL"))
        advanceUntilIdle()
        assertEquals(setOf("CTRL"), runner.latched.value)
        assertEquals(KeyMod.CTRL.bit to emptyList<Int>(), fake.kbdReports.last())

        // Emergency release must clear BOTH HID keys AND latched hold UI state.
        runner.emergencyRelease()
        advanceUntilIdle()
        assertTrue(runner.latched.value.isEmpty())
        assertEquals(0 to emptyList<Int>(), fake.kbdReports.last())
        assertEquals(listOf(0, 0, 0, 0), fake.mouseReports.last())
    }

    @Test
    fun sequenceEngineMatchesPrefixFiresMacroAndReleasesHolds() = runTest {
        val ring = ReportRing()
        val fake = FakeTransport(ring)
        var now = 10_000L
        val macro = Macro(
            id = "strike",
            name = "Precision Strike",
            sequence = listOf(Symbol.RIGHT, Symbol.UP),
            steps = listOf(Step.TapKey("D")),
        )
        val config = Config(
            settings = Settings(
                sequenceTimeoutMs = 1000,
                rotaryLatchDebounceMs = 700,
                textDelayMs = 10,
                keyJitterMs = 0,
            ),
            activeProfileId = "m",
            profiles = listOf(
                Profile(
                    id = "m",
                    name = "Macro",
                    kind = ProfileKind.MACRO,
                    single = mapOf(
                        Symbol.CROWN_CCW to Step.Hold("CTRL"),
                        Symbol.CROWN_CW to Step.Mouse(wheel = -1),
                    ),
                    macros = listOf(macro),
                ),
            ),
        )
        val runner = MacroRunner(this, { fake }, { config.settings }, ring)
        val engine = SequenceEngine({ config }, runner, this, ring, clock = { now })

        // Twist CCW twice inside debounce window -> single CTRL latch toggle (not on+off).
        engine.feed(Symbol.CROWN_CCW, InputSource.ROTARY)
        now += 100
        engine.feed(Symbol.CROWN_CCW, InputSource.ROTARY)
        advanceUntilIdle()
        assertEquals(setOf("CTRL"), runner.latched.value)

        // Wheel CW is never debounced: two twists -> two mouse wheel reports.
        engine.feed(Symbol.CROWN_CW, InputSource.ROTARY)
        engine.feed(Symbol.CROWN_CW, InputSource.ROTARY)
        advanceUntilIdle()
        assertEquals(2, fake.mouseReports.size)

        // Start sequence: prefix sets buffer & timerState.
        engine.feed(Symbol.RIGHT, InputSource.TOUCH)
        assertEquals(listOf(Symbol.RIGHT), engine.bufferState.value)
        assertNotNull(engine.timerState.value)

        // Complete sequence: fires macro, clears buffer & timer, releases CTRL hold.
        engine.feed(Symbol.UP, InputSource.TOUCH)
        advanceUntilIdle()
        assertTrue(engine.bufferState.value.isEmpty())
        assertNull(engine.timerState.value)
        assertTrue(runner.latched.value.isEmpty())
        assertEquals(EngineEvent.FiredMacro(macro), engine.event.value)
    }

    @Test
    fun sequenceTimeoutDropsPendingBufferAndReleasesHolds() = runTest {
        val ring = ReportRing()
        val fake = FakeTransport(ring)
        val config = Config(
            settings = Settings(sequenceTimeoutMs = 1000, textDelayMs = 10, keyJitterMs = 0),
            activeProfileId = "m",
            profiles = listOf(
                Profile(
                    id = "m",
                    name = "Macro",
                    kind = ProfileKind.MACRO,
                    single = mapOf(Symbol.GESTURE_1 to Step.Hold("CTRL")),
                    macros = listOf(
                        Macro("m1", "Test", listOf(Symbol.UP, Symbol.DOWN), listOf(Step.TapKey("A"))),
                    ),
                ),
            ),
        )
        val runner = MacroRunner(this, { fake }, { config.settings }, ring)
        val engine = SequenceEngine({ config }, runner, this, ring, clock = { 1000L })

        engine.feed(Symbol.GESTURE_1, InputSource.TOUCH)
        engine.feed(Symbol.UP, InputSource.TOUCH)
        assertEquals(listOf(Symbol.UP), engine.bufferState.value)
        assertEquals(setOf("CTRL"), runner.latched.value)

        advanceTimeBy(1100)
        advanceUntilIdle()

        assertTrue(engine.bufferState.value.isEmpty())
        assertNull(engine.timerState.value)
        assertTrue(runner.latched.value.isEmpty())
        assertEquals(EngineEvent.SequenceTimeout, engine.event.value)
    }
}
