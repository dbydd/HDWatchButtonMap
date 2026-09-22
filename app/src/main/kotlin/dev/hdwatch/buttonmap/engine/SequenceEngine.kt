package dev.hdwatch.buttonmap.engine

import dev.hdwatch.buttonmap.config.Config
import dev.hdwatch.buttonmap.config.ConfigRepository
import dev.hdwatch.buttonmap.config.Macro
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

/**
 * Helldivers-style input state machine.
 *
 * Every normalized [Symbol] passes through [feed]. A symbol either
 *  - completes a macro sequence (exact suffix match) → run macro, clear buffer;
 *  - is still a proper prefix of some enabled macro  → keep buffering with a
 *    timeout that silently drops the half-typed sequence;
 *  - matches nothing                                  → buffer resets and the
 *    current symbol alone is retried once, then falls through to its single
 *    mapping (or reports Unmapped).
 *
 * A length-1 macro therefore behaves exactly like a single mapping, which lets
 * config authors choose per symbol.
 */
class SequenceEngine(
    private val repo: ConfigRepository,
    private val runner: MacroRunner,
    private val scope: CoroutineScope,
    private val ring: ReportRing,
) {

    private val lock = Any()
    private var buffer: List<Symbol> = emptyList()
    private var timeoutJob: Job? = null

    private val _bufferFlow = MutableStateFlow<List<Symbol>>(emptyList())
    val bufferState: StateFlow<List<Symbol>> = _bufferFlow.asStateFlow()

    private val _event = MutableStateFlow<EngineEvent>(EngineEvent.Idle)
    val event: StateFlow<EngineEvent> = _event.asStateFlow()

    fun feed(symbol: Symbol) = synchronized(lock) {
        val cfg = repo.config.value
        val attempt = buffer + symbol
        val macros = cfg.macros.filter { it.enabled && it.sequence.isNotEmpty() }

        matchExact(macros, attempt)?.let { macro ->
            commitAttempt(attempt)
            finishBuffer()
            ring.log("ENG  ${render(attempt)} -> macro '${macro.name}'")
            _event.value = EngineEvent.FiredMacro(macro)
            runner.runMacro(macro)
            return@synchronized
        }

        if (isPrefix(macros, attempt)) {
            commitAttempt(attempt)
            _event.value = EngineEvent.Pending(attempt)
            ring.log("ENG  pend ${render(attempt)}")
            restartTimeout(cfg)
            return@synchronized
        }

        // mismatch: Helldivers rule — restart with the current symbol
        if (attempt.size > 1) {
            ring.log("ENG  ${render(attempt)} no match; restart on '${symbol.code}'")
            feedLockedRetry(symbol)
            return@synchronized
        }
        commitAttempt(attempt)
        fireSingle(cfg, symbol)
    }

    /** Second chance path after a mismatch restart; never recurses further. */
    private fun feedLockedRetry(symbol: Symbol) {
        val cfg = repo.config.value
        val macros = cfg.macros.filter { it.enabled && it.sequence.isNotEmpty() }
        matchExact(macros, listOf(symbol))?.let { macro ->
            buffer = emptyList()
            _bufferFlow.value = emptyList()
            _event.value = EngineEvent.FiredMacro(macro)
            runner.runMacro(macro)
            return
        }
        buffer = listOf(symbol)
        _bufferFlow.value = buffer
        if (isPrefix(macros, buffer)) {
            _event.value = EngineEvent.Pending(buffer)
            restartTimeout(cfg)
        } else {
            fireSingle(cfg, symbol)
        }
    }

    private fun fireSingle(cfg: Config, symbol: Symbol) {
        finishBuffer()
        val step = cfg.single[symbol]
        if (step == null) {
            _event.value = EngineEvent.Unmapped(symbol)
            ring.log("ENG  '${symbol.code}' unmapped")
            return
        }
        _event.value = EngineEvent.FiredSingle(symbol, step)
        ring.log("ENG  '${symbol.code}' -> single $step")
        runner.runStep(step)
    }

    /** Cancel any in-flight sequence (screen switches). */
    fun reset() = synchronized(lock) {
        timeoutJob?.cancel()
        timeoutJob = null
        buffer = emptyList()
        _bufferFlow.value = emptyList()
        _event.value = EngineEvent.Idle
    }

    private fun commitAttempt(attempt: List<Symbol>) {
        timeoutJob?.cancel()
        buffer = attempt
        _bufferFlow.value = attempt
    }

    private fun finishBuffer() {
        timeoutJob?.cancel()
        timeoutJob = null
        buffer = emptyList()
        _bufferFlow.value = emptyList()
    }

    private fun restartTimeout(cfg: Config) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(cfg.settings.sequenceTimeoutMs.coerceIn(500, 15_000))
            synchronized(lock) {
                if (buffer.isNotEmpty()) {
                    ring.log("ENG  timeout, drop ${render(buffer)}")
                    finishBuffer()
                    _event.value = EngineEvent.SequenceTimeout
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
}
