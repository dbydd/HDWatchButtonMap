package dev.hdwatch.buttonmap.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/** Self-built shake: [PEAKS] |a| peaks over [THRESHOLD] inside [WINDOW_MS] fire once, then [COOLDOWN_MS] mutes. */
class ImuShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private val peakTimes = ArrayDeque<Long>()
    private var lastFiredAt = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        val magnitude = Math.sqrt(
            (event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]).toDouble(),
        ).toFloat()
        if (magnitude < THRESHOLD) return

        val now = System.currentTimeMillis()
        peakTimes.addLast(now)
        while (peakTimes.isNotEmpty() && now - peakTimes.first() > WINDOW_MS) peakTimes.removeFirst()
        if (peakTimes.size >= PEAKS && now - lastFiredAt >= COOLDOWN_MS) {
            peakTimes.clear()
            lastFiredAt = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        /** Per-axis m/s²; a firm wrist flick clears this on every axis. */
        const val THRESHOLD = 18f

        /** Peaks that must land inside the window to count as a shake. */
        const val PEAKS = 4

        /** Sliding window that separates a burst of peaks from a one-off bump. */
        const val WINDOW_MS = 800L

        /** Mute after a fired shake; a burst of flicks stays single-shot. */
        const val COOLDOWN_MS = 1200L
    }
}
