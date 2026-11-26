package dev.hdwatch.buttonmap.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavTest {

    @Test
    fun navigatesThroughSettingsHubAndSubPagesAndPopsCleanly() {
        val nav = AppNav()
        assertEquals(Route.Pad, nav.current)

        nav.push(Route.Menu)
        nav.push(Route.Settings)
        nav.push(Route.SettingsDetail(SettingsSection.ROTARY))
        assertEquals(Route.SettingsDetail(SettingsSection.ROTARY), nav.current)

        nav.pop()
        assertEquals(Route.Settings, nav.current)

        nav.push(Route.SettingsDetail(SettingsSection.CONFIG))
        assertEquals(Route.SettingsDetail(SettingsSection.CONFIG), nav.current)

        nav.popToPad()
        assertEquals(Route.Pad, nav.current)

        // Popping at root stays safely on Pad.
        nav.pop()
        assertEquals(Route.Pad, nav.current)
    }
}
