package dev.hdwatch.buttonmap.engine

import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.Macro
import dev.hdwatch.buttonmap.config.Settings
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.hid.HidKey
import dev.hdwatch.buttonmap.hid.HidPage
import dev.hdwatch.buttonmap.hid.HidReports
import dev.hdwatch.buttonmap.hid.KeyTable
import dev.hdwatch.buttonmap.hid.ReportRing
import dev.hdwatch.buttonmap.hid.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Executes steps against the active transport and keeps the live HID input
 * state (held keys, held mouse buttons) so KeyDown/KeyUp and drag macros work.
 */
class MacroRunner(
    private val scope: CoroutineScope,
    private val transports: TransportManager,
    private val settings: () -> Settings,
    private val ring: ReportRing,
) {

    private data class HeldKey(val key: HidKey, val mods: Set<KeyMod>)

    private val heldKeys = LinkedHashSet<HeldKey>()
    /** KeyDown steps currently latched by a slot or a gesture. */
    private val holds = LinkedHashSet<Step.KeyDown>()

    private val _latched = MutableStateFlow<Set<String>>(emptySet())

    /** Key names currently latched — the dial lights its slots from this. */
    val latched: StateFlow<Set<String>> = _latched.asStateFlow()

    private fun syncLatched() {
        _latched.value = holds.map { it.key }.toSet()
    }
    private var mouseButtons = 0

    fun runMacro(macro: Macro) {
        // Same trick as runStep: the first step hits the wire immediately.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ring.log("MACRO ${macro.name} x${macro.repeat} (${macro.steps.size} steps)")
            repeat(macro.repeat) {
                macro.steps.forEach { execute(it) }
            }
        }
    }

    fun runStep(step: Step) {
        // UNDISPATCHED: the press/send part of the step runs inline on the
        // caller's thread, so the HID report leaves the watch one hop earlier;
        // only suspensions (delays) resume on the scope's dispatcher.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { execute(step) }
    }

    /**
     * Hold-style step: a KeyDown goes down now and stays down until
     * [releaseHold]; any other step kind just executes on press. This is what
     * lets a dial slot (or a gesture) act as a held CTRL.
     */
    fun holdStep(step: Step) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            when (step) {
                is Step.KeyDown -> {
                    holds += step
                    press(step.key, step.mods)
                    sendKeyboard()
                    syncLatched()
                }
                else -> execute(step)
            }
        }
    }

    /** Counterpart of [holdStep]: lifts a held KeyDown. */
    fun releaseHold(step: Step) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            when (step) {
                is Step.KeyDown -> {
                    holds -= step
                    release(step.key, step.mods)
                    sendKeyboard()
                    syncLatched()
                }
                else -> Unit
            }
        }
    }

    /**
     * Gesture-driven hold: a fist clench presses CTRL, the next one lifts it.
     * Sensors report one-shot detections, so a toggle is the only honest
     * mapping for a held modifier.
     */
    fun toggleHold(step: Step.KeyDown) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (holds.contains(step)) {
                holds -= step
                release(step.key, step.mods)
            } else {
                holds += step
                press(step.key, step.mods)
            }
            sendKeyboard()
            syncLatched()
        }
    }

    /**
     * Drop every hold this runner still owns — called when a sequence lands,
     * times out, or the pad goes away. Nothing stays stuck on the host.
     */
    fun releaseHolds() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (holds.isEmpty()) return@launch
            val n = holds.size
            holds.toList().forEach { h -> release(h.key, h.mods) }
            holds.clear()
            sendKeyboard()
            syncLatched()
            ring.log("RUN  holds released ($n)")
        }
    }

    /** Kill switch: every held key/button up. */
    fun emergencyRelease() {
        scope.launch {
            heldKeys.clear()
            sendKeyboard()
            mouseButtons = 0
            transports.forSettings(settings()).sendMouseReport(0, 0, 0, 0)
            ring.log("RUN  emergency release")
        }
    }

    private suspend fun execute(step: Step) {
        when (step) {
            is Step.TapKey -> tapKey(step.key, step.mods)
            is Step.KeyDown -> {
                press(step.key, step.mods)
                sendKeyboard()
            }
            is Step.KeyUp -> {
                release(step.key, step.mods)
                sendKeyboard()
            }
            is Step.TypeText -> typeText(step.text)
            is Step.Wait -> delay(step.ms.coerceIn(0, 10_000))
            is Step.Mouse -> {
                val mask = step.buttons.mapNotNull { HidReports.mouseButtonBit(it) }
                    .fold(0) { acc, bit -> acc or bit }
                mouseButtons = mask
                transports.forSettings(settings()).sendMouseReport(mask, step.dx, step.dy, step.wheel)
            }
            is Step.MouseClick -> clickMouse(step.button, step.count)
            Step.MouseRelease -> {
                mouseButtons = 0
                transports.forSettings(settings()).sendMouseReport(0, 0, 0, 0)
            }
            is Step.Hold -> toggleHold(Step.KeyDown(step.key, step.mods))
            is Step.ConsumerKey -> consumerKey(step.usage)
        }
    }

    private suspend fun tapKey(name: String, mods: Set<KeyMod>) {
        if (!press(name, mods)) return
        sendKeyboard()
        delay(settings().textDelayMs.coerceIn(5, 500))
        release(name, mods)
        sendKeyboard()
    }

    private suspend fun clickMouse(button: String, count: Int) {
        val bit = HidReports.mouseButtonBit(button) ?: run {
            ring.log("RUN  unknown mouse button '$button'")
            return
        }
        val t = transports.forSettings(settings())
        repeat(count.coerceIn(1, 10)) {
            mouseButtons = mouseButtons or bit
            t.sendMouseReport(mouseButtons, 0, 0, 0)
            delay(25)
            mouseButtons = mouseButtons and bit.inv()
            t.sendMouseReport(mouseButtons, 0, 0, 0)
            delay(35)
        }
    }

    private suspend fun consumerKey(name: String) {
        val key = KeyTable.resolve(name) ?: run {
            ring.log("RUN  unknown consumer key '$name'")
            return
        }
        val t = transports.forSettings(settings())
        t.sendConsumerReport(key.usage)
        delay(40)
        t.sendConsumerReport(0)
    }

    private suspend fun typeText(text: String) {
        val gap = settings().textDelayMs.coerceIn(5, 500)
        for (c in text) {
            val plan = KeyTable.forChar(c) ?: run {
                ring.log("RUN  char '$c' unmapped")
                continue
            }
            tapKey(plan.key, plan.mods)
            delay(gap)
        }
    }

    private fun press(name: String, mods: Set<KeyMod>): Boolean {
        val key = KeyTable.resolve(name) ?: run {
            ring.log("RUN  unknown key '$name'")
            return false
        }
        if (key.page == HidPage.CONSUMER) {
            // consumer page keys cannot ride the keyboard report
            scope.launch { consumerKey(name) }
            return false
        }
        heldKeys.add(HeldKey(key, mods))
        return true
    }

    private fun release(name: String, mods: Set<KeyMod>) {
        val key = KeyTable.resolve(name) ?: return
        heldKeys.remove(HeldKey(key, mods))
    }

    private suspend fun sendKeyboard() {
        var mods = 0
        val usages = mutableListOf<Int>()
        for (h in heldKeys) {
            h.mods.forEach { mods = mods or it.bit }
            usages += h.key.usage
        }
        val overflow = usages.size - 6
        if (overflow > 0) {
            ring.log("RUN  keyboard rollover: dropping $overflow key(s)")
            repeat(overflow) { usages.removeAt(usages.size - 1) }
        }
        transports.forSettings(settings()).sendKeyboardReport(mods, usages)
    }
}
