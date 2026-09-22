package dev.hdwatch.buttonmap.hid

/**
 * Composite HID report descriptor (keyboard + mouse + consumer control) and
 * report builders for the three input reports.
 *
 * Report layout (report IDs keep the three collections separable on hosts
 * that demand it; hosts that don't care simply route by ID):
 *   ID 1 keyboard : [modifier-byte, reserved 0x00, keycode x6]        (8 bytes)
 *   ID 2 mouse    : [buttons, dx(i8), dy(i8), wheel(i8)]             (4 bytes)
 *   ID 3 consumer : [usage16 little-endian]                          (2 bytes)
 */
object HidReports {

    const val REPORT_KEYBOARD = 1
    const val REPORT_MOUSE = 2
    const val REPORT_CONSUMER = 3

    /** SDP subclass bitfield for a keyboard+mouse combo device. */
    val SDP_SUBCLASS_COMBO: Byte = 0xC0.toByte()

    const val MOUSE_LEFT = 0x01
    const val MOUSE_RIGHT = 0x02
    const val MOUSE_MIDDLE = 0x04
    const val MOUSE_BACK = 0x08
    const val MOUSE_FORWARD = 0x10

    private fun b(vararg v: Int): ByteArray = v.map { (it and 0xFF).toByte() }.toByteArray()

    val DESCRIPTOR: ByteArray = b(
        // ---------------- Keyboard (report ID 1) ----------------
        0x05, 0x01, //  Usage Page (Generic Desktop)
        0x09, 0x06, //  Usage (Keyboard)
        0xA1, 0x01, //  Collection (Application)
        0x85, REPORT_KEYBOARD, //     Report ID (1)
        // modifiers: 8 boolean bits, usage 0xE0..0xE7
        0x05, 0x07, //    Usage Page (Keyboard/Keypad)
        0x19, 0xE0, //    Usage Minimum (LeftControl)
        0x29, 0xE7, //    Usage Maximum (RightGUI)
        0x15, 0x00, //    Logical Minimum (0)
        0x25, 0x01, //    Logical Maximum (1)
        0x75, 0x01, //    Report Size (1)
        0x95, 0x08, //    Report Count (8)
        0x81, 0x02, //    Input (Data,Var,Abs)
        // reserved byte
        0x75, 0x08, //    Report Size (8)
        0x95, 0x01, //    Report Count (1)
        0x81, 0x03, //    Input (Const,Var,Abs)
        // keycodes: 6-slot array, usage 0x00..0xFF
        0x05, 0x07, //    Usage Page (Keyboard/Keypad)
        0x19, 0x00, //    Usage Minimum (0)
        0x2A, 0xFF, 0x00, // Usage Maximum (0xFF)
        0x15, 0x00, //    Logical Minimum (0)
        0x26, 0xFF, 0x00, // Logical Maximum (0xFF)
        0x75, 0x08, //    Report Size (8)
        0x95, 0x06, //    Report Count (6)
        0x81, 0x00, //    Input (Data,Array)
        0xC0, //  End Collection

        // ---------------- Mouse (report ID 2) ----------------
        0x05, 0x01, //  Usage Page (Generic Desktop)
        0x09, 0x02, //  Usage (Mouse)
        0xA1, 0x01, //  Collection (Application)
        0x85, REPORT_MOUSE, //        Report ID (2)
        0x09, 0x01, //    Usage (Pointer)
        0xA1, 0x00, //    Collection (Physical)
        // buttons: 5 bits + 3-bit pad
        0x05, 0x09, //      Usage Page (Button)
        0x19, 0x01, //      Usage Minimum (Button 1)
        0x29, 0x05, //      Usage Maximum (Button 5)
        0x15, 0x00, //      Logical Minimum (0)
        0x25, 0x01, //      Logical Maximum (1)
        0x75, 0x01, //      Report Size (1)
        0x95, 0x05, //      Report Count (5)
        0x81, 0x02, //      Input (Data,Var,Abs)
        0x75, 0x03, //      Report Size (3)
        0x95, 0x01, //      Report Count (1)
        0x81, 0x03, //      Input (Const,Var,Abs)
        // X, Y, Wheel: signed relative bytes
        0x05, 0x01, //      Usage Page (Generic Desktop)
        0x09, 0x30, //      Usage (X)
        0x09, 0x31, //      Usage (Y)
        0x15, 0x81, //      Logical Minimum (-127)
        0x25, 0x7F, //      Logical Maximum (127)
        0x75, 0x08, //      Report Size (8)
        0x95, 0x02, //      Report Count (2)
        0x81, 0x06, //      Input (Data,Var,Rel)
        0x09, 0x38, //      Usage (Wheel)
        0x75, 0x08, //      Report Size (8)
        0x95, 0x01, //      Report Count (1)
        0x81, 0x06, //      Input (Data,Var,Rel)
        0xC0, //    End Collection
        0xC0, //  End Collection

        // ---------------- Consumer control (report ID 3) ----------------
        0x05, 0x0C, //  Usage Page (Consumer)
        0x09, 0x01, //  Usage (Consumer Control)
        0xA1, 0x01, //  Collection (Application)
        0x85, REPORT_CONSUMER, //     Report ID (3)
        0x15, 0x00, //    Logical Minimum (0)
        0x26, 0xFF, 0x03, // Logical Maximum (0x3FF)
        0x19, 0x00, //    Usage Minimum (0)
        0x2A, 0xFF, 0x03, // Usage Maximum (0x3FF)
        0x75, 0x10, //    Report Size (16)
        0x95, 0x01, //    Report Count (1)
        0x81, 0x00, //    Input (Data,Array)
        0xC0, //  End Collection
    )

    fun keyboardReport(modifierBits: Int, keyUsages: List<Int>): ByteArray {
        require(keyUsages.size <= 6) { "keyboard report holds max 6 keycodes" }
        val r = ByteArray(8)
        r[0] = (modifierBits and 0xFF).toByte()
        r[1] = 0
        keyUsages.forEachIndexed { i, usage -> r[2 + i] = (usage and 0xFF).toByte() }
        return r
    }

    fun mouseReport(buttonBits: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttonBits and 0x1F).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte(),
    )

    fun consumerReport(usage: Int): ByteArray = byteArrayOf(
        (usage and 0xFF).toByte(),
        ((usage shr 8) and 0xFF).toByte(),
    )

    fun mouseButtonBit(name: String): Int? = when (name.lowercase()) {
        "left" -> MOUSE_LEFT
        "right" -> MOUSE_RIGHT
        "middle" -> MOUSE_MIDDLE
        "back" -> MOUSE_BACK
        "forward" -> MOUSE_FORWARD
        else -> null
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
