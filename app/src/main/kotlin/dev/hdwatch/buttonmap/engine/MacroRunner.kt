package dev.hdwatch.buttonmap.engine

import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.Macro
import dev.hdwatch.buttonmap.config.Settings
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.hid.HidKey
import dev.hdwatch.buttonmap.hid.HidPage
import dev.hdwatch.buttonmap.hid.HidReports
import dev.hdwatch.buttonmap.hid.HidTransport
import dev.hdwatch.buttonmap.hid.KeyTable
import dev.hdwatch.buttonmap.hid.ReportRing
import dev.hdwatch.buttonmap.hid.TransportManager
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Executes steps against the active transport and keeps the live HID input
 * state (held keys, held mouse buttons) so KeyDown/KeyUp and drag macros work.
 *
 * All mutable key/mouse state is serialized under [stateMutex] so concurrent
 * UNDISPATCHED taps, rotary bursts, and macro coroutines never race.
 */
class MacroRunner(
    private val scope: CoroutineScope,
    private val transportProvider: (Settings) -> HidTransport,
    private val settings: () -> Settings,
    private val ring: ReportRing,
) {

    constructor(
        scope: CoroutineScope,
        transports: TransportManager,
        settings: () -> Settings,
        ring: ReportRing,
    ) : this(scope, { s -> transports.forSettings(s) }, settings, ring)

    private data class HeldKey(val key: HidKey, val mods: Set<KeyMod>)

    private val stateMutex = Mutex()
    private val heldKeys = LinkedHashSet<HeldKey>()

    /** KeyDown steps currently latched by a slot, crown, or gesture. */
    private val holds = LinkedHashSet<Step.KeyDown>()
    private var mouseButtons = 0

    private val _latched = MutableStateFlow<Set<String>>(emptySet())

    /** Key names currently latched — the dial lights its slots & badge from this. */
    val latched: StateFlow<Set<String>> = _latched.asStateFlow()

    private fun syncLatchedLocked() {
        _latched.value = holds.map { it.key }.toSet()
    }

    fun runMacro(macro: Macro) {
        // Same trick as runStep: the first step hits the wire immediately.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ring.log("MACRO ${macro.name} x${macro.repeat} (${macro.steps.size} steps)")
            repeat(macro.repeat) {
                macro.steps.forEachIndexed { index, step ->
                    execute(step)
                    if (index < macro.steps.lastIndex) jitterGap()
                }
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
                    val snapshot = stateMutex.withLock {
                        holds += step
                        pressLocked(step.key, step.mods)
                        syncLatchedLocked()
                        keyboardSnapshotLocked()
                    }
                    sendKeyboard(snapshot)
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
                    val snapshot = stateMutex.withLock {
                        holds -= step
                        releaseLocked(step.key, step.mods)
                        syncLatchedLocked()
                        keyboardSnapshotLocked()
                    }
                    sendKeyboard(snapshot)
                }
                else -> Unit
            }
        }
    }

    /**
     * Gesture/crown-driven hold: first trigger presses CTRL, next lifts it.
     * Sensors report one-shot detections, so a toggle is the only honest
     * mapping for a held modifier.
     */
    fun toggleHold(step: Step.KeyDown) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val snapshot = stateMutex.withLock {
                if (holds.contains(step)) {
                    holds -= step
                    releaseLocked(step.key, step.mods)
                } else {
                    holds += step
                    pressLocked(step.key, step.mods)
                }
                syncLatchedLocked()
                keyboardSnapshotLocked()
            }
            sendKeyboard(snapshot)
        }
    }

    /**
     * Drop every hold this runner still owns — called when a sequence lands,
     * times out, or the pad goes away. Nothing stays stuck on the host.
     */
    fun releaseHolds() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = stateMutex.withLock {
                if (holds.isEmpty()) return@withLock null
                val n = holds.size
                holds.toList().forEach { h -> releaseLocked(h.key, h.mods) }
                holds.clear()
                syncLatchedLocked()
                n to keyboardSnapshotLocked()
            } ?: return@launch
            sendKeyboard(result.second)
            ring.log("RUN  holds released (${result.first})")
        }
    }

    /** Randomised breathing room between steps; see Settings.keyJitterMs. */
    private suspend fun jitterGap() {
        val bound = settings().keyJitterMs
        if (bound > 0) delay(Random.nextLong(0L, bound + 1))
    }

    /** Kill switch: every held key, latched hold, and mouse button up. */
    fun emergencyRelease() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val snapshot = stateMutex.withLock {
                heldKeys.clear()
                holds.clear()
                mouseButtons = 0
                syncLatchedLocked()
                keyboardSnapshotLocked()
            }
            sendKeyboard(snapshot)
            transportProvider(settings()).sendMouseReport(0, 0, 0, 0)
            ring.log("RUN  emergency release")
        }
    }

    private suspend fun execute(step: Step) {
        when (step) {
            is Step.TapKey -> tapKey(step.key, step.mods)
            is Step.KeyDown -> {
                val snapshot = stateMutex.withLock {
                    if (!pressLocked(step.key, step.mods)) return@withLock null
                    keyboardSnapshotLocked()
                }
                if (snapshot != null) sendKeyboard(snapshot)
            }
            is Step.KeyUp -> {
                val snapshot = stateMutex.withLock {
                    releaseLocked(step.key, step.mods)
                    keyboardSnapshotLocked()
                }
                sendKeyboard(snapshot)
            }
            is Step.TypeText -> typeText(step.text)
            is Step.Wait -> delay(step.ms.coerceIn(0, 10_000))
            is Step.Mouse -> {
                val mask = stateMutex.withLock {
                    val m = step.buttons.mapNotNull { HidReports.mouseButtonBit(it) }
                        .fold(0) { acc, bit -> acc or bit }
                    mouseButtons = m
                    m
                }
                transportProvider(settings()).sendMouseReport(mask, step.dx, step.dy, step.wheel)
            }
            is Step.MouseClick -> clickMouse(step.button, step.count)
            Step.MouseRelease -> {
                stateMutex.withLock { mouseButtons = 0 }
                transportProvider(settings()).sendMouseReport(0, 0, 0, 0)
            }
            is Step.Hold -> toggleHold(Step.KeyDown(step.key, step.mods))
            is Step.ConsumerKey -> consumerKey(step.usage)
        }
    }

    private suspend fun tapKey(name: String, mods: Set<KeyMod>) {
        val downSnapshot = stateMutex.withLock {
            if (!pressLocked(name, mods)) return@withLock null
            keyboardSnapshotLocked()
        } ?: return
        sendKeyboard(downSnapshot)
        delay(settings().textDelayMs.coerceIn(5, 500))
        val upSnapshot = stateMutex.withLock {
            releaseLocked(name, mods)
            keyboardSnapshotLocked()
        }
        sendKeyboard(upSnapshot)
    }

    private suspend fun clickMouse(button: String, count: Int) {
        val bit = HidReports.mouseButtonBit(button) ?: run {
            ring.log("RUN  unknown mouse button '$button'")
            return
        }
        val t = transportProvider(settings())
        repeat(count.coerceIn(1, 10)) {
            val downMask = stateMutex.withLock {
                mouseButtons = mouseButtons or bit
                mouseButtons
            }
            t.sendMouseReport(downMask, 0, 0, 0)
            delay(25)
            val upMask = stateMutex.withLock {
                mouseButtons = mouseButtons and bit.inv()
                mouseButtons
            }
            t.sendMouseReport(upMask, 0, 0, 0)
            delay(35)
        }
    }

    private suspend fun consumerKey(name: String) {
        val key = KeyTable.resolve(name) ?: run {
            ring.log("RUN  unknown consumer key '$name'")
            return
        }
        val t = transportProvider(settings())
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

    private fun pressLocked(name: String, mods: Set<KeyMod>): Boolean {
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

    private fun releaseLocked(name: String, mods: Set<KeyMod>) {
        val key = KeyTable.resolve(name) ?: return
        heldKeys.remove(HeldKey(key, mods))
    }

    private data class KeyboardSnapshot(val mods: Int, val usages: List<Int>)

    private fun keyboardSnapshotLocked(): KeyboardSnapshot {
        var mods = 0
        val usages = mutableListOf<Int>()
        for (h in heldKeys) {
            h.mods.forEach { mods = mods or it.bit }
            // USB HID 1.11: usages 0xE0..0xE7 (LeftControl..RightGUI) belong in
            // the modifier bitmask byte, not the 6-key rollover array. Setting
            // the modifier bit ensures CTRL/SHIFT/ALT/GUI holds work across
            // Windows, macOS, Linux, iPadOS, and Android hosts.
            if (h.key.usage in MODIFIER_USAGE_MIN..MODIFIER_USAGE_MAX) {
                mods = mods or (1 shl (h.key.usage - MODIFIER_USAGE_MIN))
            } else {
                usages += h.key.usage
            }
        }
        val overflow = usages.size - 6
        if (overflow > 0) {
            ring.log("RUN  keyboard rollover: dropping $overflow key(s)")
            repeat(overflow) { usages.removeAt(usages.size - 1) }
        }
        return KeyboardSnapshot(mods, usages)
    }

    private suspend fun sendKeyboard(snapshot: KeyboardSnapshot) {
        transportProvider(settings()).sendKeyboardReport(snapshot.mods, snapshot.usages)
    }

    private companion object {
        const val MODIFIER_USAGE_MIN = 0xE0
        const val MODIFIER_USAGE_MAX = 0xE7
    }
}
