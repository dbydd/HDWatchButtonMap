package dev.hdwatch.buttonmap.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSwitchStateTest {

    @Test
    fun switchingWhileAnotherHostConnectedDisconnectsOldFirstThenConnectsNew() {
        val state = HostSwitchState(initialPreferred = "AA:AA")
        state.onConnected("AA:AA")
        assertTrue(state.shouldWatchdogReconnect())

        // Request switch to BB:BB while AA:AA is still connected in BT stack.
        val plan = state.requestSwitch(targetAddress = "BB:BB", activeStackAddresses = listOf("AA:AA"))
        assertEquals(listOf("AA:AA"), plan.disconnectFirst)
        assertNull(plan.connectImmediately)
        assertEquals("BB:BB", state.preferredAddress)
        assertEquals("BB:BB", state.pendingSwitchAddress)
        // Watchdog must not interfere while switching.
        assertFalse(state.shouldWatchdogReconnect())

        // Once AA:AA finishes disconnecting, coordinator hands back BB:BB to connect.
        val next = state.onDisconnected("AA:AA", remainingActiveAddresses = emptyList())
        assertEquals("BB:BB", next)
        assertNull(state.pendingSwitchAddress)

        state.onConnected("BB:BB")
        assertEquals("BB:BB", state.connectedAddress)
        assertTrue(state.shouldWatchdogReconnect())
    }

    @Test
    fun switchingWhenIdleConnectsImmediately() {
        val state = HostSwitchState(initialPreferred = "AA:AA")
        val plan = state.requestSwitch(targetAddress = "BB:BB", activeStackAddresses = emptyList())
        assertTrue(plan.disconnectFirst.isEmpty())
        assertEquals("BB:BB", plan.connectImmediately)
        assertEquals("BB:BB", state.preferredAddress)
    }

    @Test
    fun manualDisconnectAndClearLockStopWatchdogFromYankingBack() {
        val state = HostSwitchState(initialPreferred = "AA:AA")
        state.onConnected("AA:AA")

        // Manual disconnect pauses watchdog while keeping preferred host for later manual reconnect.
        state.requestManualDisconnect()
        assertFalse(state.shouldWatchdogReconnect())
        assertEquals("AA:AA", state.preferredAddress)

        // Manual reconnect resumes watchdog.
        state.requestReconnect("AA:AA")
        assertTrue(state.shouldWatchdogReconnect())

        // Clear lock wipes cached host completely and pauses watchdog.
        state.clearLock()
        assertNull(state.preferredAddress)
        assertNull(state.connectedAddress)
        assertNull(state.pendingSwitchAddress)
        assertFalse(state.shouldWatchdogReconnect())
    }

    @Test
    fun fallbackTimerConsumesPendingSwitchOnceIfDisconnectCallbackDrops() {
        val state = HostSwitchState()
        state.requestSwitch("BB:BB", activeStackAddresses = listOf("AA:AA"))

        assertEquals("BB:BB", state.consumePendingFallback())
        assertNull(state.consumePendingFallback())
    }
}
