package dev.hdwatch.buttonmap.engine

import dev.hdwatch.buttonmap.config.Config
import dev.hdwatch.buttonmap.config.ConfigRepository
import dev.hdwatch.buttonmap.config.Macro
import dev.hdwatch.buttonmap.config.Profile
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.hid.ReportRing
import dev.hdwatch.buttonmap.input.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Outcome of the last engine decision; drives the pad HUD feedback. */
sealed interface EngineEvent {
    data object Idle : EngineEvent
    /** Symbol appended to a pending sequence; waiting for more input. */
    data class Pending(val buffer: List<Symbol>) : EngineEvent
    data class FiredMacro(val macro: Macro) : EngineEvent
    data class FiredSingle(val symbol: Symbol, val step: Step) : EngineEvent
    data object SequenceTimeout : EngineEvent
    data class Unmapped(val symbol: Symbol) : EngineEvent
}

/** Where a symbol came from; rotary stays silent because it fires densely. */
enum class InputSource { TOUCH, ROTARY, SENSOR }

/**
 * Input state machine over the *active profile*.
 *
 * KEYS profile: every symbol fires its single mapping immediately (press
 * what you get). MACRO profile runs the Helldivers rules:
 *  - buffer ends with an enabled macro's sequence → run it, clear buffer;
 *  - buffer is a proper prefix → keep buffering with an idle timeout that
 *    silently drops the half-typed sequence;
 *  - otherwise the buffer restarts at the current symbol; if even that alone
 *    matches nothing, its single mapping fires (Unmapped when not bound).
 */
class SequenceEngine(
    private val repo: ConfigRepository,
    private val runner: MacroRunner,
    private val scope: CoroutineScope,
    private val ring: ReportRing,
    private val onEvent: (EngineEvent, InputSource) -> Unit = { _, _ -> },
) {

    private val lock = Any()
    private var buffer: List<Symbol> = emptyList()
    private var timeoutJob: Job? = null
    @Volatile private var lastSource: InputSource = InputSource.TOUCH
    private val lastLatchAt = HashMap<Symbol, Long>()

    private val _bufferFlow = MutableStateFlow<List<Symbol>>(emptyList())
    val bufferState: StateFlow<List<Symbol>> = _bufferFlow.asStateFlow()

    /**
     * Sequence so far with rotary entries dropped: the hub's hint panel keys
     * off this, so twisting the crown never changes what the menu suggests.
     */
    private val _hintFlow = MutableStateFlow<List<Symbol>>(emptyList())
    val hintState: StateFlow<List<Symbol>> = _hintFlow.asStateFlow()

    private var bufferSources: List<InputSource> = emptyList()

    private val _event = MutableStateFlow<EngineEvent>(EngineEvent.Idle)
    val event: StateFlow<EngineEvent> = _event.asStateFlow()

    private fun emit(e: EngineEvent) {
        _event.value = e
        onEvent(e, lastSource)
    }

    fun feed(symbol: Symbol, source: InputSource = InputSource.TOUCH) = synchronized(lock) {
        lastSource = source
        val cfg = repo.config.value
        val profile = cfg.activeProfile

        if (profile.kind == ProfileKind.KEYS) {
            finishBuffer()
            fireSingle(cfg, profile, symbol)
            return@synchronized
        }

        val macros = profile.macros.filter { it.enabled && it.sequence.isNotEmpty() }
        val attempt = buffer + symbol
        val attemptSources = bufferSources + source

        matchExact(macros, attempt)?.let { macro ->
            commitAttempt(attempt, attemptSources)
            finishBuffer()
            ring.log("ENG  ${render(attempt)} -> macro '${macro.name}'")
            emit(EngineEvent.FiredMacro(macro))
            runner.releaseHolds()
            runner.runMacro(macro)
            return@synchronized
        }

        if (isPrefix(macros, attempt)) {
            commitAttempt(attempt, attemptSources)
            emit(EngineEvent.Pending(attempt))
            ring.log("ENG  pend ${render(attempt)}")
            restartTimeout(cfg)
            return@synchronized
        }

        if (attempt.size > 1) {
            ring.log("ENG  ${render(attempt)} no match; restart on '${symbol.code}'")
            retrySolo(cfg, profile, symbol)
            return@synchronized
        }
        commitAttempt(attempt, attemptSources)
        fireSingle(cfg, profile, symbol)
    }

    /** Second chance after a mismatch restart; never recurses further. */
    private fun retrySolo(cfg: Config, profile: Profile, symbol: Symbol) {
        val macros = profile.macros.filter { it.enabled && it.sequence.isNotEmpty() }
        matchExact(macros, listOf(symbol))?.let { macro ->
            finishBuffer()
            emit(EngineEvent.FiredMacro(macro))
            runner.runMacro(macro)
            return
        }
        buffer = listOf(symbol)
        bufferSources = listOf(lastSource)
        publish()
        if (isPrefix(macros, buffer)) {
            emit(EngineEvent.Pending(buffer))
            restartTimeout(cfg)
        } else {
            fireSingle(cfg, profile, symbol)
        }
    }

    private fun fireSingle(cfg: Config, profile: Profile, symbol: Symbol) {
        finishBuffer()
        val step = profile.single[symbol]
        if (step == null) {
            emit(EngineEvent.Unmapped(symbol))
            ring.log("ENG  '${symbol.code}' unmapped")
            return
        }
        // A latch bound to the crown must not flicker: twisting three detents
        // the same way is one gesture, so repeats inside the window are
        // swallowed instead of toggling the key on/off/on/off.
        if (step is Step.Hold && lastSource == InputSource.ROTARY) {
            val now = android.os.SystemClock.uptimeMillis()
            val prev = lastLatchAt[symbol] ?: 0L
            if (now - prev < LATCH_WINDOW_MS) {
                ring.log("ENG  '${symbol.code}' latch repeat ignored")
                return
            }
            lastLatchAt[symbol] = now
        }
        emit(EngineEvent.FiredSingle(symbol, step))
        runner.runStep(step)
    }

    /** Cancel any in-flight sequence (screen switches, profile change). */
    fun reset() = synchronized(lock) {
        timeoutJob?.cancel()
        timeoutJob = null
        buffer = emptyList()
        bufferSources = emptyList()
        publish()
        _event.value = EngineEvent.Idle
        runner.releaseHolds()
    }

    private fun commitAttempt(attempt: List<Symbol>, sources: List<InputSource>) {
        timeoutJob?.cancel()
        buffer = attempt
        bufferSources = sources
        publish()
    }

    private fun finishBuffer() {
        timeoutJob?.cancel()
        timeoutJob = null
        buffer = emptyList()
        bufferSources = emptyList()
        publish()
    }

    private fun publish() {
        _bufferFlow.value = buffer
        _hintFlow.value = if (bufferSources.contains(InputSource.ROTARY)) {
            buffer.filterIndexed { i, _ -> bufferSources.getOrNull(i) != InputSource.ROTARY }
        } else {
            buffer
        }
    }

    private fun restartTimeout(cfg: Config) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(cfg.settings.sequenceTimeoutMs.coerceIn(500, 15_000))
            synchronized(lock) {
                if (buffer.isNotEmpty()) {
                    ring.log("ENG  timeout, drop ${render(buffer)}")
                    finishBuffer()
                    emit(EngineEvent.SequenceTimeout)
                    runner.releaseHolds()
                }
            }
        }
    }

    private fun matchExact(macros: List<Macro>, attempt: List<Symbol>): Macro? =
        macros.lastOrNull { it.sequence == attempt }

    private fun isPrefix(macros: List<Macro>, attempt: List<Symbol>): Boolean =
        macros.any { m ->
            m.sequence.size > attempt.size && m.sequence.subList(0, attempt.size) == attempt
        }

    private fun render(seq: List<Symbol>): String = seq.joinToString(" ") { it.code }
    private companion object {
        /** Same-direction detents inside this window count as one latch. */
        const val LATCH_WINDOW_MS = 700L
    }
}
