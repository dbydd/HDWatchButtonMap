package dev.hdwatch.buttonmap.hid

import dev.hdwatch.buttonmap.config.KeyMod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsAndKeyTableTest {

    @Test
    fun keyboardReportEncodesModifiersAndUsages() {
        val report = HidReports.keyboardReport(
            modifierBits = KeyMod.CTRL.bit or KeyMod.SHIFT.bit,
            keyUsages = listOf(0x04, 0x06),
        )
        assertEquals(8, report.size)
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x04, 0x06, 0x00, 0x00, 0x00, 0x00),
            report,
        )
    }

    @Test
    fun mouseReportClampsSignedDeltas() {
        val report = HidReports.mouseReport(
            buttonBits = HidReports.MOUSE_LEFT or HidReports.MOUSE_RIGHT,
            dx = 500,
            dy = -500,
            wheel = -1,
        )
        assertArrayEquals(
            byteArrayOf(0x03, 127, (-127).toByte(), (-1).toByte()),
            report,
        )
    }

    @Test
    fun consumerReportEncodesLittleEndian16Bit() {
        val report = HidReports.consumerReport(0x0223) // AC_HOME
        assertArrayEquals(byteArrayOf(0x23, 0x02), report)
    }

    @Test
    fun keyTableResolvesAliasesAndMediaKeys() {
        val ctrl = KeyTable.resolve("ctrl")
        assertNotNull(ctrl)
        assertEquals(0xE0, ctrl!!.usage)
        assertEquals(HidPage.KEYBOARD, ctrl.page)

        val volUp = KeyTable.resolve("volume_up")
        assertNotNull(volUp)
        assertEquals(0x00E9, volUp!!.usage)
        assertEquals(HidPage.CONSUMER, volUp.page)

        assertNull(KeyTable.resolve("NON_EXISTENT_KEY"))
    }

    @Test
    fun keyTableCharPlanHandlesUpperLowerAndSymbols() {
        val upper = KeyTable.forChar('A')
        assertEquals(KeyTable.TypedChar("A", setOf(KeyMod.SHIFT)), upper)

        val lower = KeyTable.forChar('z')
        assertEquals(KeyTable.TypedChar("Z", emptySet()), lower)

        val bang = KeyTable.forChar('!')
        assertEquals(KeyTable.TypedChar("1", setOf(KeyMod.SHIFT)), bang)

        assertTrue(HidReports.DESCRIPTOR.isNotEmpty())
    }
}
