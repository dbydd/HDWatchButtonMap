package dev.hdwatch.buttonmap.hid

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide line ring for transports, runner, engine and raw input.
 * Shown on the log screen; doubles as the emulator-side proof of behavior.
 */
class ReportRing(private val capacity: Int = 300) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private val lines = ArrayDeque<String>()
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 256)

    val history: List<String> get() = synchronized(lines) { lines.toList() }
    val recent: SharedFlow<String> = flow.asSharedFlow()

    fun log(text: String) {
        val line = synchronized(lines) {
            val formatted = "${timeFormat.format(Date())}  $text"
            lines.addLast(formatted)
            while (lines.size > capacity) lines.removeFirst()
            formatted
        }
        runCatching { Log.d("HDMAP", line) }
        flow.tryEmit(line)
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
