package dev.hdwatch.buttonmap.hid

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransportKind { LOGGING, BLUETOOTH }

/**
 * Machine-readable transport state. UI must branch on this, never on the
 * human text: the label/detail strings are localized.
 */
enum class TransportState {
    LOGGING,
    STARTING,
    DISCONNECTED,
    REGISTER_FAILED,
    CONNECTING,
    CONNECTED,
    NEEDS_PERMISSION,
    UNAVAILABLE,
    NO_ADAPTER,
}

/** Transport state plus its localized, ready-to-render text. */
data class TransportStatus(
    val kind: TransportKind,
    val state: TransportState,
    val label: String,
    val detail: String = "",
    val hostName: String? = null,
)

/**
 * Sends HID input reports to the host. Implementations must serialize
 * internally (a per-report mutex) — callers fire from macro coroutines.
 */
interface HidTransport {
    val kind: TransportKind
    val status: StateFlow<TransportStatus>

    /** Ring buffer of recent report/log lines for the input-log screen. */
    val reportLog: SharedFlow<String>
    val logHistory: List<String>

    suspend fun sendKeyboardReport(modifierBits: Int, keyUsages: List<Int>): Boolean
    suspend fun sendMouseReport(buttonBits: Int, dx: Int, dy: Int, wheel: Int): Boolean
    suspend fun sendConsumerReport(usage: Int): Boolean

    /** Kick a reconnect attempt toward the last/selected host, if possible. */
    fun tryReconnect() {}

    fun close() {}
}

/**
 * Log-only transport used by the emulator and `forceLoggingTransport`.
 * Reports are hex-formatted into [reportLog]; send always succeeds.
 */
class LoggingTransport(
    private val app: android.app.Application,
    private val ring: ReportRing,
) : HidTransport {
    override val kind = TransportKind.LOGGING
    override val status = MutableStateFlow(
        TransportStatus(
            TransportKind.LOGGING,
            TransportState.LOGGING,
            app.getString(dev.hdwatch.buttonmap.R.string.hid_log_label),
            app.getString(dev.hdwatch.buttonmap.R.string.hid_log_detail),
        ),
    )

    override val reportLog: SharedFlow<String> = ring.recent
    override val logHistory: List<String> get() = ring.history

    private fun log(line: String) = ring.log(line)

    override suspend fun sendKeyboardReport(modifierBits: Int, keyUsages: List<Int>): Boolean {
        log("KBD  ${HidReports.hex(HidReports.keyboardReport(modifierBits, keyUsages))}")
        return true
    }

    override suspend fun sendMouseReport(buttonBits: Int, dx: Int, dy: Int, wheel: Int): Boolean {
        log("MOU  ${HidReports.hex(HidReports.mouseReport(buttonBits, dx, dy, wheel))}")
        return true
    }

    override suspend fun sendConsumerReport(usage: Int): Boolean {
        log("CON  ${HidReports.hex(HidReports.consumerReport(usage))} (usage 0x%04X)".format(usage))
        return true
    }

    fun note(text: String) = log("NOTE $text")
}
