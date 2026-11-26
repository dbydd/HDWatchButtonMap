package dev.hdwatch.buttonmap.hid

/**
 * Pure-Kotlin state machine governing single-host Bluetooth HID switching and
 * watchdog auto-reconnect suppression.
 *
 * Android's BluetoothHidDevice stack (`bta_hd`) only supports one active host
 * link at a time: calling `connect(newHost)` before `oldHost` finishes
 * disconnecting fails silently or drops the request, and a background watchdog
 * that ignores manual disconnects immediately yanks the link back to the old
 * cached host. This coordinator serializes disconnect → connect transitions
 * and respects explicit user disconnects / cache clears.
 */
class HostSwitchState(initialPreferred: String? = null) {

    data class SwitchPlan(
        /** Addresses that must be disconnected first before connecting. */
        val disconnectFirst: List<String>,
        /** Non-null when safe to connect immediately without waiting. */
        val connectImmediately: String?,
    )

    @Volatile var preferredAddress: String? = initialPreferred
        private set

    @Volatile var connectedAddress: String? = null
        private set

    @Volatile var pendingSwitchAddress: String? = null
        private set

    /** True after explicit user disconnect or cache clear; blocks watchdog auto-reconnect. */
    @Volatile var manualDisconnect: Boolean = false
        private set

    /**
     * User tapped a specific bonded host.
     * If any *other* host is currently connected or connecting, queue the
     * target in [pendingSwitchAddress] and disconnect the old host(s) first.
     */
    @Synchronized
    fun requestSwitch(targetAddress: String, activeStackAddresses: Collection<String>): SwitchPlan {
        manualDisconnect = false
        preferredAddress = targetAddress
        val others = activeStackAddresses.filter { it != targetAddress }
        return if (others.isNotEmpty()) {
            pendingSwitchAddress = targetAddress
            SwitchPlan(disconnectFirst = others, connectImmediately = null)
        } else {
            pendingSwitchAddress = null
            SwitchPlan(disconnectFirst = emptyList(), connectImmediately = targetAddress)
        }
    }

    /** User tapped Reconnect: re-enable auto-link and clear manual pause. */
    @Synchronized
    fun requestReconnect(targetAddress: String) {
        manualDisconnect = false
        preferredAddress = targetAddress
        pendingSwitchAddress = null
    }

    /** User tapped Disconnect: pause watchdog so it doesn't yank back in 4s. */
    @Synchronized
    fun requestManualDisconnect() {
        manualDisconnect = true
        pendingSwitchAddress = null
    }

    /** User tapped Clear Host Lock: wipe preferred cache & pause watchdog. */
    @Synchronized
    fun clearLock() {
        manualDisconnect = true
        preferredAddress = null
        pendingSwitchAddress = null
        connectedAddress = null
    }

    @Synchronized
    fun onConnected(address: String) {
        connectedAddress = address
        preferredAddress = address
        manualDisconnect = false
        if (pendingSwitchAddress == address) {
            pendingSwitchAddress = null
        }
    }

    /**
     * A host finished disconnecting. Returns the queued [pendingSwitchAddress]
     * if no conflicting hosts remain connected, so the transport can now issue
     * `connect(nextHost)` cleanly.
     */
    @Synchronized
    fun onDisconnected(address: String, remainingActiveAddresses: Collection<String>): String? {
        if (connectedAddress == address) {
            connectedAddress = remainingActiveAddresses.firstOrNull()
        }
        val queued = pendingSwitchAddress ?: return null
        val conflicting = remainingActiveAddresses.any { it != queued }
        if (conflicting) return null
        pendingSwitchAddress = null
        return queued
    }

    /** Fallback when a disconnect callback never arrives within the grace window. */
    @Synchronized
    fun consumePendingFallback(): String? {
        val queued = pendingSwitchAddress ?: return null
        pendingSwitchAddress = null
        return queued
    }

    /** Watchdog only auto-reconnects when the user has not paused/cleared the link. */
    @Synchronized
    fun shouldWatchdogReconnect(): Boolean =
        !manualDisconnect && pendingSwitchAddress == null && preferredAddress != null
}
