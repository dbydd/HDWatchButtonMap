package dev.hdwatch.buttonmap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.hdwatch.buttonmap.hid.TransportKind

/**
 * Keeps the HID registration alive while the app is backgrounded and retries
 * the link to the last host.
 *
 * Without this, the Bluetooth stack unregisters the HID app the moment the
 * UI leaves the foreground (IMPORTANCE_VISIBLE rule) — which both drops an
 * established link and makes the watch reject the host's reconnect attempts
 * during the re-register window (measured on GW6C: "Reject Incoming HID
 * Connection" right after app switching). A started foreground service keeps
 * the process importance below that cutoff.
 */
class HidForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var linkWatchdog: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        startWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        linkWatchdog?.let { handler.removeCallbacks(it) }
        linkWatchdog = null
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HID 遥控", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HD 遥控运行中")
            .setContentText("保持 HID 注册与主机链路")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        runCatching {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure {
            // Older behavior path; still better than no service.
            runCatching { startForeground(NOTIF_ID, notification) }
        }
    }

    /** Periodic health check: re-register if the stack dropped us, reconnect if the link fell. */
    private fun startWatchdog() {
        stopWatchdogLoop()
        val loop = object : Runnable {
            override fun run() {
                val app = application as? HdApp
                if (app != null) {
                    val transport = app.transports.active.value
                    if (transport.kind == TransportKind.BLUETOOTH) {
                        (transport as dev.hdwatch.buttonmap.hid.BluetoothHidTransport).ensureLink()
                    }
                }
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
        linkWatchdog = loop
        handler.postDelayed(loop, WATCHDOG_MS)
    }

    private fun stopWatchdogLoop() {
        linkWatchdog?.let { handler.removeCallbacks(it) }
    }

    companion object {
        private const val CHANNEL_ID = "hid_live"
        private const val NOTIF_ID = 42
        private const val WATCHDOG_MS = 4_000L

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, HidForegroundService::class.java)
            if (enabled) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { runCatching { context.startService(intent) } }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
