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
    private var mouseButtons = 0

    fun runMacro(macro: Macro) {
        scope.launch {
            ring.log("MACRO ${macro.name} x${macro.repeat} (${macro.steps.size} steps)")
            repeat(macro.repeat) {
                macro.steps.forEach { execute(it) }
            }
        }
    }

    fun runStep(step: Step) {
        scope.launch { execute(step) }
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
