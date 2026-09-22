package dev.hdwatch.buttonmap.ui

import android.os.VibrationEffect
import android.os.Vibrator
import dev.hdwatch.buttonmap.engine.EngineEvent

/**
 * Watch haptics. Patterns are short on purpose: the watch vibrates hard and
 * long buzzes drain battery and feel like errors.
 */
class Haptics(private val vibrator: Vibrator?, private val onError: (String) -> Unit = {}) {

    fun press() = wave(8L)
    fun tick() = wave(14L)
    fun single() = wave(24L)

    /** Two rising taps: sequence landed and macro running. */
    fun macro() = wave(VibrationEffect.createWaveform(longArrayOf(0, 24, 70, 40), -1))

    /** Flat double: nothing mapped, or sequence dropped. */
    fun error() = wave(VibrationEffect.createWaveform(longArrayOf(0, 45, 45, 45), -1))

    fun onEvent(event: EngineEvent) = when (event) {
        is EngineEvent.Pending -> tick()
        is EngineEvent.FiredSingle -> single()
        is EngineEvent.FiredMacro -> macro()
        is EngineEvent.Unmapped -> error()
        EngineEvent.SequenceTimeout -> error()
        else -> Unit
    }

    private fun wave(effect: VibrationEffect?) {
        val v = vibrator ?: return
        runCatching { if (v.hasVibrator()) v.vibrate(effect) }
            .onFailure { onError("vibrate failed: ${it.message}") }
    }

    private fun wave(ms: Long) = wave(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}
