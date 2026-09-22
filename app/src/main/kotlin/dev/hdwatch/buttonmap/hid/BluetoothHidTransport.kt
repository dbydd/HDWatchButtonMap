package dev.hdwatch.buttonmap.hid

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real HID-over-Bluetooth transport: the watch registers itself as a combo
 * keyboard+mouse HID device, the desktop host pairs to it and receives
 * reports (same route WearMouse ships on Play).
 *
 * Stack facts this implementation relies on:
 *  - registerApp/sendReport/connect need only BLUETOOTH_CONNECT (API 31+ runtime).
 *  - setConnectionPolicy is privileged-only; never called. Hosts initiate the
 *    connection, we react in onConnectionStateChanged.
 *  - The app must stay foreground or the stack unregisters the HID app.
 */
class BluetoothHidTransport(
    private val app: Application,
    private val ring: ReportRing,
) : HidTransport {

    override val kind = TransportKind.BLUETOOTH

    private val _status = MutableStateFlow(
        TransportStatus(TransportKind.BLUETOOTH, "蓝牙HID", "正在连接协议栈")
    )
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    override val reportLog: SharedFlow<String> = ring.recent
    override val logHistory: List<String> get() = ring.history

    private val adapter: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false
    private var target: BluetoothDevice? = null

    /** Last sent payload per report id, replayed when the host GET_REPORTs. */
    private val lastReports = HashMap<Int, ByteArray>()
    private val sendMutex = Mutex()

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        SDP_NAME,
        SDP_DESCRIPTION,
        SDP_PROVIDER,
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidReports.DESCRIPTOR,
    )

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            ring.log("BT  profile proxy connected")
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            registered = false
            target = null
            _status.value = TransportStatus(TransportKind.BLUETOOTH, "蓝牙HID", "协议栈断开")
            ring.log("BT  profile proxy disconnected")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(plugState: BluetoothDevice?, available: Boolean) {
            registered = available
            _status.value = if (available) {
                TransportStatus(TransportKind.BLUETOOTH, "蓝牙HID", "已注册，等待主机连接")
            } else {
                TransportStatus(TransportKind.BLUETOOTH, "蓝牙HID", "注册失败（app需在前台）")
            }
            ring.log("BT  app registered=$available")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            ring.log("BT  state=${stateName(state)} ${describe(device)}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    target = device
                    _status.value = TransportStatus(
                        TransportKind.BLUETOOTH, "已连接", "HID 主机在线", hostName(device),
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (target == device) target = firstConnectedDevice()
                    _status.value = TransportStatus(TransportKind.BLUETOOTH, "蓝牙HID", "等待主机连接")
                }
                else -> Unit
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val payload = lastReports[id.toInt()] ?: zeroReport(id.toInt())
            runCatching { hidDevice?.replyReport(device, type, id, payload) }
                .onFailure { ring.log("BT  replyReport failed: ${it.message}") }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // LED output reports etc.: acknowledge without acting.
            runCatching { hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) = Unit
    }

    init {
        val a = adapter
        when {
            a == null -> _status.value =
                TransportStatus(TransportKind.BLUETOOTH, "不可用", "本机无蓝牙适配器")
            !hasConnectPermission() -> _status.value =
                TransportStatus(TransportKind.BLUETOOTH, "缺少权限", "需要蓝牙连接权限")
            else -> {
                val ok = runCatching {
                    a.getProfileProxy(app, profileListener, BluetoothProfile.HID_DEVICE)
                }.getOrDefault(false)
                if (!ok) _status.value = TransportStatus(
                    TransportKind.BLUETOOTH, "不可用", "HID Device profile 不支持（OEM 未开启）"
                )
            }
        }
    }

    private fun register() {
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) {
            _status.value = TransportStatus(TransportKind.BLUETOOTH, "缺少权限", "需要蓝牙连接权限")
            return
        }
        val ok = runCatching {
            dev.registerApp(sdpSettings, null, null, { command -> command.run() }, callback)
        }.getOrDefault(false)
        ring.log("BT  registerApp=$ok")
        if (!ok) _status.value = TransportStatus(
            TransportKind.BLUETOOTH, "注册失败", "registerApp 返回 false；保持前台后重试"
        )
    }

    fun hasConnectPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Bonded devices we could offer as hosts (needs BLUETOOTH_CONNECT). */
    fun bondedHosts(): List<BluetoothDevice> =
        if (!hasConnectPermission()) emptyList()
        else runCatching { adapter?.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())

    fun hostLabel(device: BluetoothDevice): String = describe(device)

    override fun tryReconnect() {
        val dev = hidDevice ?: return
        val candidate = target ?: firstConnectedDevice()
            ?: bondedHosts().firstOrNull()
            ?: return
        if (!hasConnectPermission()) return
        ring.log("BT  connect -> ${describe(candidate)}")
        runCatching { dev.connect(candidate) }.onFailure { ring.log("BT  connect failed ${it.message}") }
    }

    /**
     * Watchdog entry point (HidForegroundService): heal registration first,
     * then the link. Cheap enough to run every few seconds.
     */
    fun ensureLink() {
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) return
        if (!registered) {
            ring.log("BT  watchdog: re-register")
            register()
            return
        }
        val host = target ?: bondedHosts().firstOrNull {
            runCatching { dev.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        } ?: bondedHosts().firstOrNull() ?: return
        target = host
        val state = runCatching { dev.getConnectionState(host) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            ring.log("BT  watchdog: reconnect ${describe(host)}")
            runCatching { dev.connect(host) }
        }
    }

    fun disconnectTarget() {
        val dev = hidDevice ?: return
        val t = target ?: return
        if (!hasConnectPermission()) return
        runCatching { dev.disconnect(t) }
    }

    fun reRegister() {
        if (!hasConnectPermission()) return
        val dev = hidDevice ?: return
        runCatching { dev.unregisterApp() }
        registered = false
        register()
    }

    override suspend fun sendKeyboardReport(modifierBits: Int, keyUsages: List<Int>): Boolean =
        send(HidReports.REPORT_KEYBOARD, HidReports.keyboardReport(modifierBits, keyUsages))

    override suspend fun sendMouseReport(buttonBits: Int, dx: Int, dy: Int, wheel: Int): Boolean =
        send(HidReports.REPORT_MOUSE, HidReports.mouseReport(buttonBits, dx, dy, wheel))

    override suspend fun sendConsumerReport(usage: Int): Boolean =
        send(HidReports.REPORT_CONSUMER, HidReports.consumerReport(usage))

    private suspend fun send(reportId: Int, payload: ByteArray): Boolean =
        sendMutex.withLock {
            val dev = hidDevice
            val host = target ?: firstConnectedDevice()
            if (dev == null || host == null || !registered) {
                ring.log("TX  #$reportId dropped (no host)")
                return@withLock false
            }
            val ok = runCatching { dev.sendReport(host, reportId, payload) }.getOrDefault(false)
            if (ok) lastReports[reportId] = payload
            ring.log("TX  #$reportId ${HidReports.hex(payload)} ${if (ok) "ok" else "FAIL"}")
            ok
        }

    private fun firstConnectedDevice(): BluetoothDevice? {
        val dev = hidDevice ?: return null
        if (!hasConnectPermission()) return null
        return runCatching {
            dev.getDevicesMatchingConnectionStates(
                intArrayOf(BluetoothProfile.STATE_CONNECTED)
            ).firstOrNull()
        }.getOrNull()
    }

    private fun zeroReport(id: Int): ByteArray = when (id) {
        HidReports.REPORT_KEYBOARD -> HidReports.keyboardReport(0, emptyList())
        HidReports.REPORT_MOUSE -> HidReports.mouseReport(0, 0, 0, 0)
        HidReports.REPORT_CONSUMER -> HidReports.consumerReport(0)
        else -> ByteArray(1)
    }

    private fun describe(device: BluetoothDevice): String =
        runCatching { device.name ?: device.address }.getOrDefault(device.address)

    private fun hostName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "STATE_$state"
    }

    override fun close() {
        val dev = hidDevice
        if (dev != null && hasConnectPermission()) runCatching { dev.unregisterApp() }
        adapter?.let { a ->
            runCatching { hidDevice?.let { a.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        }
        hidDevice = null
        registered = false
        target = null
    }

    companion object {
        const val SDP_NAME = "HD Watch Remote"
        const val SDP_DESCRIPTION = "Wear OS button map HID"
        const val SDP_PROVIDER = "hdwatch"

        /** Intent action the host needs while pairing: make us discoverable. */
        const val REQUEST_DISCOVERABLE_SECONDS = 120
    }
}
