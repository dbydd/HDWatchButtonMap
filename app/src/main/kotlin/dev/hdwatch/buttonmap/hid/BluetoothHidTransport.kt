package dev.hdwatch.buttonmap.hid

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.input.WatchDefaults
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
 *  - Only one HID host can be active at a time; switching hosts must disconnect
 *    the previous host and wait for STATE_DISCONNECTED before connecting the
 *    new one (coordinated by [switchState]).
 *  - Explicit user disconnect / lock clear pauses watchdog auto-reconnect so
 *    the foreground service never yanks back to a stale cached host.
 */
class BluetoothHidTransport(
    private val app: Application,
    private val ring: ReportRing,
) : HidTransport {

    override val kind = TransportKind.BLUETOOTH

    private val _status = MutableStateFlow(
        TransportStatus(
            TransportKind.BLUETOOTH,
            TransportState.STARTING,
            app.getString(R.string.hid_bt_label),
            app.getString(R.string.hid_st_starting),
        ),
    )
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    override val reportLog: SharedFlow<String> = ring.recent
    override val logHistory: List<String> get() = ring.history

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("hdmap_bt", android.content.Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    private val switchState = HostSwitchState(prefs.getString(KEY_LAST_HOST, null))
    private var pendingSwitchRunnable: Runnable? = null

    private var hidDevice: BluetoothHidDevice? = null
    private var proxyRequested = false
    private var registered = false
    private var target: BluetoothDevice? = null

    private val _activeHostAddress = MutableStateFlow(switchState.preferredAddress)
    /** Address of the currently connected or preferred host (for UI highlight). */
    val activeHostAddress: StateFlow<String?> = _activeHostAddress.asStateFlow()

    private val _connectedHostAddress = MutableStateFlow<String?>(null)
    /** Address of the actively connected HID host (null when disconnected). */
    val connectedHostAddress: StateFlow<String?> = _connectedHostAddress.asStateFlow()

    private val _switchingHostAddress = MutableStateFlow<String?>(null)
    /** Non-null while waiting for an old host to drop before connecting a new one. */
    val switchingHostAddress: StateFlow<String?> = _switchingHostAddress.asStateFlow()

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
            proxyRequested = false
            hidDevice = proxy as BluetoothHidDevice
            ring.log("BT  profile proxy connected")
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxyRequested = false
            hidDevice = null
            registered = false
            target = null
            _connectedHostAddress.value = null
            _switchingHostAddress.value = null
            cancelSwitchTimer()
            _status.value = TransportStatus(
                TransportKind.BLUETOOTH,
                TransportState.DISCONNECTED,
                app.getString(R.string.hid_bt_label),
                app.getString(R.string.hid_st_proxy_down),
            )
            ring.log("BT  profile proxy disconnected")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(plugState: BluetoothDevice?, available: Boolean) {
            registered = available
            ring.log("BT  app registered=$available")
            if (available) {
                val pendingAddr = switchState.pendingSwitchAddress
                if (pendingAddr != null) {
                    val dev = findBondedByAddress(pendingAddr)
                    if (dev != null) {
                        connectDeviceDirect(dev)
                        return
                    }
                }
                _status.value = TransportStatus(
                    TransportKind.BLUETOOTH,
                    TransportState.DISCONNECTED,
                    app.getString(R.string.hid_bt_label),
                    app.getString(R.string.hid_st_registered),
                )
            } else {
                _status.value = TransportStatus(
                    TransportKind.BLUETOOTH,
                    TransportState.REGISTER_FAILED,
                    app.getString(R.string.hid_bt_label),
                    app.getString(R.string.hid_st_register_failed),
                )
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val addr = safeAddress(device)
            ring.log("BT  state=${stateName(state)} ${describe(device)}")
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    _status.value = TransportStatus(
                        TransportKind.BLUETOOTH,
                        TransportState.CONNECTING,
                        app.getString(R.string.hid_st_connecting_label),
                        app.getString(R.string.hid_st_connecting_detail, describe(device)),
                        hostName(device),
                    )
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    cancelSwitchTimer()
                    target = device
                    if (addr != null) {
                        switchState.onConnected(addr)
                        persistHost(addr)
                    }
                    _connectedHostAddress.value = addr
                    _switchingHostAddress.value = null
                    _status.value = TransportStatus(
                        TransportKind.BLUETOOTH,
                        TransportState.CONNECTED,
                        app.getString(R.string.hid_st_connected_label),
                        app.getString(R.string.hid_st_connected_detail),
                        hostName(device),
                    )
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (target?.address == addr) target = firstConnectedDevice()
                    val remaining = activeStackDevices().mapNotNull { safeAddress(it) }
                    val nextAddr = if (addr != null) switchState.onDisconnected(addr, remaining) else null
                    _connectedHostAddress.value = switchState.connectedAddress

                    if (nextAddr != null) {
                        cancelSwitchTimer()
                        val nextDevice = findBondedByAddress(nextAddr)
                        if (nextDevice != null) {
                            ring.log("BT  switch ready -> ${describe(nextDevice)}")
                            connectDeviceDirect(nextDevice)
                            return
                        }
                    }

                    val stillConnected = firstConnectedDevice()
                    if (stillConnected != null) {
                        target = stillConnected
                        _connectedHostAddress.value = safeAddress(stillConnected)
                        _status.value = TransportStatus(
                            TransportKind.BLUETOOTH,
                            TransportState.CONNECTED,
                            app.getString(R.string.hid_st_connected_label),
                            app.getString(R.string.hid_st_connected_detail),
                            hostName(stillConnected),
                        )
                    } else {
                        _switchingHostAddress.value = null
                        _status.value = TransportStatus(
                            TransportKind.BLUETOOTH,
                            TransportState.DISCONNECTED,
                            app.getString(R.string.hid_bt_label),
                            if (switchState.manualDisconnect) {
                                app.getString(R.string.hid_st_disconnected_manual)
                            } else {
                                app.getString(R.string.hid_st_waiting_host)
                            },
                        )
                    }
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
        attachProxyOrReportError()
    }

    private fun attachProxyOrReportError() {
        val a = adapter
        when {
            a == null -> _status.value = TransportStatus(
                TransportKind.BLUETOOTH,
                TransportState.NO_ADAPTER,
                app.getString(R.string.hid_st_unavailable_label),
                app.getString(R.string.hid_st_no_adapter),
            )
            !hasConnectPermission() -> _status.value = TransportStatus(
                TransportKind.BLUETOOTH,
                TransportState.NEEDS_PERMISSION,
                app.getString(R.string.hid_st_need_permission_label),
                app.getString(R.string.hid_st_need_permission),
            )
            hidDevice == null && !proxyRequested -> {
                proxyRequested = true
                val ok = runCatching {
                    a.getProfileProxy(app, profileListener, BluetoothProfile.HID_DEVICE)
                }.getOrDefault(false)
                if (!ok) {
                    proxyRequested = false
                    _status.value = TransportStatus(
                        TransportKind.BLUETOOTH,
                        TransportState.UNAVAILABLE,
                        app.getString(R.string.hid_st_unavailable_label),
                        app.getString(R.string.hid_st_profile_missing),
                    )
                }
            }
        }
    }

    /** Re-attach HID profile proxy if permissions were granted after startup or BT cycled. */
    fun ensureProxy() {
        if (hidDevice == null) attachProxyOrReportError()
    }

    private fun register() {
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) {
            _status.value = TransportStatus(
                TransportKind.BLUETOOTH,
                TransportState.NEEDS_PERMISSION,
                app.getString(R.string.hid_st_need_permission_label),
                app.getString(R.string.hid_st_need_permission),
            )
            return
        }
        val ok = runCatching {
            dev.registerApp(sdpSettings, null, null, { command -> command.run() }, callback)
        }.getOrDefault(false)
        ring.log("BT  registerApp=$ok")
        if (!ok) _status.value = TransportStatus(
            TransportKind.BLUETOOTH,
            TransportState.REGISTER_FAILED,
            app.getString(R.string.hid_st_register_failed_label),
            app.getString(R.string.hid_st_register_false),
        )
    }

    fun hasConnectPermission(): Boolean =
        WatchDefaults.hasBluetoothConnectPermission(app)

    /** Bonded devices we could offer as hosts (needs BLUETOOTH_CONNECT on API 31+). */
    fun bondedHosts(): List<BluetoothDevice> =
        if (!hasConnectPermission()) emptyList()
        else runCatching { adapter?.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())

    fun hostLabel(device: BluetoothDevice): String = describe(device)

    /**
     * Switch cleanly to [device]: if any other HID host is currently connected
     * or connecting in the BT stack, disconnect it first and wait for
     * STATE_DISCONNECTED (with a safety fallback) before calling connect().
     */
    fun connectTo(device: BluetoothDevice) {
        ensureProxy()
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) return
        val addr = safeAddress(device) ?: return

        persistHost(addr)
        val activeDevices = activeStackDevices()
        val activeAddrs = activeDevices.mapNotNull { safeAddress(it) }
        val plan = switchState.requestSwitch(addr, activeAddrs)

        if (!registered) {
            ring.log("BT  not registered yet; re-registering for ${describe(device)}")
            _switchingHostAddress.value = addr
            register()
            return
        }

        if (plan.disconnectFirst.isNotEmpty()) {
            _switchingHostAddress.value = addr
            _status.value = TransportStatus(
                TransportKind.BLUETOOTH,
                TransportState.CONNECTING,
                app.getString(R.string.hid_st_switching_label),
                app.getString(R.string.hid_st_connecting_detail, describe(device)),
                hostName(device),
            )
            activeDevices
                .filter { safeAddress(it) in plan.disconnectFirst }
                .forEach { old ->
                    ring.log("BT  disconnect old -> ${describe(old)}")
                    runCatching { dev.disconnect(old) }
                }
            scheduleSwitchFallback(device)
        } else {
            connectDeviceDirect(device)
        }
    }

    override fun tryReconnect() {
        ensureProxy()
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) return
        val candidate = preferredHost() ?: return
        val addr = safeAddress(candidate)
        if (addr != null) {
            switchState.requestReconnect(addr)
            persistHost(addr)
        }
        if (!registered) {
            register()
            return
        }
        connectDeviceDirect(candidate)
    }

    /**
     * Watchdog entry point (HidForegroundService): heal registration first,
     * then reconnect ONLY if the user has not manually disconnected or cleared
     * the host lock.
     */
    fun ensureLink() {
        ensureProxy()
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) return
        if (!registered) {
            ring.log("BT  watchdog: re-register")
            register()
            return
        }
        if (!switchState.shouldWatchdogReconnect()) return
        val host = preferredHost() ?: return
        target = host
        val state = runCatching { dev.getConnectionState(host) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            ring.log("BT  watchdog: reconnect ${describe(host)}")
            runCatching { dev.connect(host) }
        }
    }

    /** Disconnect all active HID hosts and pause watchdog auto-reconnect. */
    fun disconnectTarget() {
        cancelSwitchTimer()
        switchState.requestManualDisconnect()
        _switchingHostAddress.value = null
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) return
        val toDrop = LinkedHashSet<BluetoothDevice>()
        target?.let { toDrop += it }
        toDrop += activeStackDevices()
        target = null
        toDrop.forEach { host ->
            ring.log("BT  manual disconnect -> ${describe(host)}")
            runCatching { dev.disconnect(host) }
        }
        if (toDrop.isEmpty()) {
            _connectedHostAddress.value = null
            _status.value = TransportStatus(
                TransportKind.BLUETOOTH,
                TransportState.DISCONNECTED,
                app.getString(R.string.hid_bt_label),
                app.getString(R.string.hid_st_disconnected_manual),
            )
        }
    }

    /**
     * Hard break for any cached/locked host: disconnects all active links,
     * wipes persisted preferred-host address, pauses watchdog, and re-registers
     * the HID SDP app cleanly so the user can freely pick any host.
     */
    fun clearHostLock() {
        cancelSwitchTimer()
        switchState.clearLock()
        prefs.edit().remove(KEY_LAST_HOST).apply()
        _activeHostAddress.value = null
        _connectedHostAddress.value = null
        _switchingHostAddress.value = null
        val dev = hidDevice
        if (dev != null && hasConnectPermission()) {
            val toDrop = LinkedHashSet<BluetoothDevice>()
            target?.let { toDrop += it }
            toDrop += activeStackDevices()
            toDrop.forEach { host -> runCatching { dev.disconnect(host) } }
            target = null
            ring.log("BT  host lock cleared; re-registering HID app")
            runCatching { dev.unregisterApp() }
            registered = false
            register()
        } else {
            target = null
            ensureProxy()
        }
    }

    fun reRegister() {
        ensureProxy()
        if (!hasConnectPermission()) return
        val dev = hidDevice ?: return
        cancelSwitchTimer()
        runCatching { dev.unregisterApp() }
        registered = false
        register()
    }

    private fun connectDeviceDirect(device: BluetoothDevice) {
        val dev = hidDevice ?: return
        if (!hasConnectPermission()) return
        target = device
        _switchingHostAddress.value = safeAddress(device)
        _status.value = TransportStatus(
            TransportKind.BLUETOOTH,
            TransportState.CONNECTING,
            app.getString(R.string.hid_st_connecting_label),
            app.getString(R.string.hid_st_connecting_detail, describe(device)),
            hostName(device),
        )
        ring.log("BT  connect -> ${describe(device)}")
        val ok = runCatching { dev.connect(device) }.onFailure {
            ring.log("BT  connect failed ${it.message}")
        }.getOrDefault(false)
        if (!ok) {
            ring.log("BT  connect returned false for ${describe(device)}")
            _switchingHostAddress.value = null
        }
    }

    private fun scheduleSwitchFallback(device: BluetoothDevice) {
        cancelSwitchTimer()
        val task = Runnable {
            val fallbackAddr = switchState.consumePendingFallback()
            if (fallbackAddr != null && fallbackAddr == safeAddress(device)) {
                ring.log("BT  switch grace elapsed; connecting ${describe(device)}")
                connectDeviceDirect(device)
            }
        }
        pendingSwitchRunnable = task
        mainHandler.postDelayed(task, SWITCH_DISCONNECT_GRACE_MS)
    }

    private fun cancelSwitchTimer() {
        pendingSwitchRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSwitchRunnable = null
    }

    private fun preferredHost(): BluetoothDevice? {
        val savedAddr = switchState.preferredAddress
        if (savedAddr != null) {
            findBondedByAddress(savedAddr)?.let { return it }
        }
        target?.let { return it }
        firstConnectedDevice()?.let { return it }
        return bondedHosts().firstOrNull()
    }

    private fun findBondedByAddress(address: String): BluetoothDevice? =
        bondedHosts().firstOrNull { safeAddress(it) == address }

    private fun persistHost(address: String) {
        prefs.edit().putString(KEY_LAST_HOST, address).apply()
        _activeHostAddress.value = address
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

    private fun activeStackDevices(): List<BluetoothDevice> {
        val dev = hidDevice ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        return runCatching {
            dev.getDevicesMatchingConnectionStates(
                intArrayOf(
                    BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_CONNECTING,
                ),
            )
        }.getOrDefault(emptyList())
    }

    private fun firstConnectedDevice(): BluetoothDevice? {
        val dev = hidDevice ?: return null
        if (!hasConnectPermission()) return null
        return runCatching {
            dev.getDevicesMatchingConnectionStates(
                intArrayOf(BluetoothProfile.STATE_CONNECTED),
            ).firstOrNull()
        }.getOrNull()
    }

    private fun zeroReport(id: Int): ByteArray = when (id) {
        HidReports.REPORT_KEYBOARD -> HidReports.keyboardReport(0, emptyList())
        HidReports.REPORT_MOUSE -> HidReports.mouseReport(0, 0, 0, 0)
        HidReports.REPORT_CONSUMER -> HidReports.consumerReport(0)
        else -> ByteArray(1)
    }

    private fun safeAddress(device: BluetoothDevice): String? =
        runCatching { device.address }.getOrNull()

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
        cancelSwitchTimer()
        val dev = hidDevice
        if (dev != null && hasConnectPermission()) runCatching { dev.unregisterApp() }
        adapter?.let { a ->
            runCatching { hidDevice?.let { a.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        }
        hidDevice = null
        proxyRequested = false
        registered = false
        target = null
    }

    companion object {
        const val SDP_NAME = "HD Watch Remote"
        const val SDP_DESCRIPTION = "Wear OS button map HID"
        const val SDP_PROVIDER = "hdwatch"
        private const val KEY_LAST_HOST = "last_host_addr"
        private const val SWITCH_DISCONNECT_GRACE_MS = 850L

        /** Intent action the host needs while pairing: make us discoverable. */
        const val REQUEST_DISCOVERABLE_SECONDS = 120
    }
}
