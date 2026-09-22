package dev.hdwatch.buttonmap.hid

import dev.hdwatch.buttonmap.config.KeyMod

/** Where a usage lives in the composite report. */
enum class HidPage { KEYBOARD, CONSUMER }

data class HidKey(val name: String, val usage: Int, val page: HidPage = HidPage.KEYBOARD)

/**
 * Key-name catalogue. Names are case-insensitive; the UI picker shows
 * [categories]. `usage` is the HID Keyboard/Keypad usage ID (0x04..0xE7) for
 * KEYBOARD page entries, or a Consumer Page usage (0x00B5..0x03FF) for
 * CONSUMER page entries.
 */
/** Picker grouping. The id is stable; the label is a resource. */
enum class KeyCategory { LETTERS, DIGITS, EDITING, ARROWS, PUNCTUATION, FUNCTION, NUMPAD, MODIFIERS, MEDIA }

object KeyTable {

    private val byName = LinkedHashMap<String, HidKey>()

    val categories: List<Pair<KeyCategory, List<HidKey>>> = buildList {
        add(KeyCategory.LETTERS to register(listOf(
            "A" to 0x04, "B" to 0x05, "C" to 0x06, "D" to 0x07, "E" to 0x08, "F" to 0x09,
            "G" to 0x0A, "H" to 0x0B, "I" to 0x0C, "J" to 0x0D, "K" to 0x0E, "L" to 0x0F,
            "M" to 0x10, "N" to 0x11, "O" to 0x12, "P" to 0x13, "Q" to 0x14, "R" to 0x15,
            "S" to 0x16, "T" to 0x17, "U" to 0x18, "V" to 0x19, "W" to 0x1A, "X" to 0x1B,
            "Y" to 0x1C, "Z" to 0x1D,
        )))
        add(KeyCategory.DIGITS to register(listOf(
            "1" to 0x1E, "2" to 0x1F, "3" to 0x20, "4" to 0x21, "5" to 0x22,
            "6" to 0x23, "7" to 0x24, "8" to 0x25, "9" to 0x26, "0" to 0x27,
        )))
        add(KeyCategory.EDITING to register(listOf(
            "ENTER" to 0x28, "ESC" to 0x29, "BACKSPACE" to 0x2A, "TAB" to 0x2B,
            "SPACE" to 0x2C, "CAPS_LOCK" to 0x39,
            "PRINTSCREEN" to 0x46, "SCROLL_LOCK" to 0x47, "PAUSE" to 0x48,
            "INSERT" to 0x49, "HOME" to 0x4A, "PAGE_UP" to 0x4B,
            "DELETE" to 0x4C, "END" to 0x4D, "PAGE_DOWN" to 0x4E,
        )))
        add(KeyCategory.ARROWS to register(listOf(
            "RIGHT" to 0x4F, "LEFT" to 0x50, "DOWN" to 0x51, "UP" to 0x52,
        )))
        add(KeyCategory.PUNCTUATION to register(listOf(
            "BRACE_L" to 0x2F, "BRACE_R" to 0x30, "MINUS" to 0x2D, "EQUAL" to 0x2E,
            "SEMICOLON" to 0x33, "APOSTROPHE" to 0x34, "GRAVE" to 0x35,
            "BACKSLASH" to 0x31, "COMMA" to 0x36, "PERIOD" to 0x37,
            "SLASH" to 0x38, "BACKSLASH_BS" to 0x64,
        )))
        add(KeyCategory.FUNCTION to register(buildList {
            add("F1" to 0x3A); add("F2" to 0x3B); add("F3" to 0x3C); add("F4" to 0x3D)
            add("F5" to 0x3E); add("F6" to 0x3F); add("F7" to 0x40); add("F8" to 0x41)
            add("F9" to 0x42); add("F10" to 0x43); add("F11" to 0x44); add("F12" to 0x45)
            add("F13" to 0x68); add("F14" to 0x69); add("F15" to 0x6A); add("F16" to 0x6B)
            add("F17" to 0x6C); add("F18" to 0x6D); add("F19" to 0x6E); add("F20" to 0x6F)
            add("F21" to 0x70); add("F22" to 0x71); add("F23" to 0x72); add("F24" to 0x73)
        }))
        add(KeyCategory.NUMPAD to register(buildList {
            add("NUM_LOCK" to 0x53)
            add("KP_DIVIDE" to 0x54); add("KP_MULTIPLY" to 0x55); add("KP_MINUS" to 0x56)
            add("KP_PLUS" to 0x57); add("KP_ENTER" to 0x58)
            add("KP_1" to 0x59); add("KP_2" to 0x5A); add("KP_3" to 0x5B); add("KP_4" to 0x5C)
            add("KP_5" to 0x5D); add("KP_6" to 0x5E); add("KP_7" to 0x5F); add("KP_8" to 0x60)
            add("KP_9" to 0x61); add("KP_0" to 0x62); add("KP_DOT" to 0x63)
        }))
        add(KeyCategory.MODIFIERS to register(listOf(
            "CTRL_L" to 0xE0, "SHIFT_L" to 0xE1, "ALT_L" to 0xE2, "GUI_L" to 0xE3,
            "CTRL_R" to 0xE4, "SHIFT_R" to 0xE5, "ALT_R" to 0xE6, "GUI_R" to 0xE7,
        )))
        add(KeyCategory.MEDIA to register(listOf(
            "PLAY_PAUSE" to 0x00CD, "MUTE" to 0x00E2,
            "VOL_UP" to 0x00E9, "VOL_DOWN" to 0x00EA,
            "NEXT_TRACK" to 0x00B5, "PREV_TRACK" to 0x00B6,
            "STOP" to 0x00B7, "MENU" to 0x0076,
            "SELECT" to 0x0041, "AC_HOME" to 0x0223, "AC_BACK" to 0x0224,
            "BRIGHTNESS_UP" to 0x006F, "BRIGHTNESS_DOWN" to 0x0070,
        ), HidPage.CONSUMER))
    }

    /** Alias names accepted in configs. */
    private val aliases = mapOf(
        "RETURN" to "ENTER", "DEL" to "DELETE", "BKSP" to "BACKSPACE",
        "PGUP" to "PAGE_UP", "PGDN" to "PAGE_DOWN", "PSPACE" to "PRINTSCREEN",
        "WIN" to "GUI_L", "META" to "GUI_L", "CMD" to "GUI_L", "SUPER" to "GUI_L",
        "CTRL" to "CTRL_L", "SHIFT" to "SHIFT_L", "ALT" to "ALT_L",
        "SPACEBAR" to "SPACE", "MEDIA_PLAY" to "PLAY_PAUSE",
        "VOLUME_UP" to "VOL_UP", "VOLUME_DOWN" to "VOL_DOWN", "VOLUME_MUTE" to "MUTE",
        "BACKSLASH_BS" to "INTL_BACKSLASH",
    )

    val allKeys: List<HidKey> get() = byName.values.toList()

    fun resolve(raw: String): HidKey? {
        val probe = raw.trim().uppercase()
        byName[probe]?.let { return it }
        aliases[probe]?.let { return byName[it.uppercase()] }
        return null
    }

    /** Char → key press plan for [dev.hdwatch.buttonmap.config.Step.TypeText]. */
    fun forChar(c: Char): TypedChar? = when {
        c == ' ' -> TypedChar("SPACE", emptySet())
        c == '\n' -> TypedChar("ENTER", emptySet())
        c == '\t' -> TypedChar("TAB", emptySet())
        c.isLetter() -> {
            val upper = c.isUpperCase()
            TypedChar(c.lowercase().uppercase(), if (upper) setOf(KeyMod.SHIFT) else emptySet())
        }
        c.isDigit() -> {
            // digits need shift for symbols handled below; plain digits stay plain
            TypedChar(c.toString(), emptySet())
        }
        else -> SYMBOL_CHARS[c]?.let { TypedChar(it.first, it.second) }
    }

    data class TypedChar(val key: String, val mods: Set<KeyMod>)

    private val SYMBOL_CHARS: Map<Char, Pair<String, Set<KeyMod>>> = mapOf(
        '!' to ("1" to setOf(KeyMod.SHIFT)),
        '@' to ("2" to setOf(KeyMod.SHIFT)),
        '#' to ("3" to setOf(KeyMod.SHIFT)),
        '$' to ("4" to setOf(KeyMod.SHIFT)),
        '%' to ("5" to setOf(KeyMod.SHIFT)),
        '^' to ("6" to setOf(KeyMod.SHIFT)),
        '&' to ("7" to setOf(KeyMod.SHIFT)),
        '*' to ("8" to setOf(KeyMod.SHIFT)),
        '(' to ("9" to setOf(KeyMod.SHIFT)),
        ')' to ("0" to setOf(KeyMod.SHIFT)),
        '_' to ("MINUS" to setOf(KeyMod.SHIFT)),
        '+' to ("EQUAL" to setOf(KeyMod.SHIFT)),
        '-' to ("MINUS" to emptySet()),
        '=' to ("EQUAL" to emptySet()),
        '{' to ("BRACE_L" to setOf(KeyMod.SHIFT)),
        '}' to ("BRACE_R" to setOf(KeyMod.SHIFT)),
        '[' to ("BRACE_L" to emptySet()),
        ']' to ("BRACE_R" to emptySet()),
        '|' to ("BACKSLASH" to setOf(KeyMod.SHIFT)),
        '\\' to ("BACKSLASH" to emptySet()),
        ':' to ("SEMICOLON" to setOf(KeyMod.SHIFT)),
        ';' to ("SEMICOLON" to emptySet()),
        '"' to ("APOSTROPHE" to setOf(KeyMod.SHIFT)),
        '\'' to ("APOSTROPHE" to emptySet()),
        '<' to ("COMMA" to setOf(KeyMod.SHIFT)),
        '>' to ("PERIOD" to setOf(KeyMod.SHIFT)),
        ',' to ("COMMA" to emptySet()),
        '.' to ("PERIOD" to emptySet()),
        '?' to ("SLASH" to setOf(KeyMod.SHIFT)),
        '/' to ("SLASH" to emptySet()),
        '~' to ("GRAVE" to setOf(KeyMod.SHIFT)),
        '`' to ("GRAVE" to emptySet()),
    )

    /** Modifier names accepted by combo parsing, exposed for the picker UI. */
    val modNames: List<String> = listOf("CTRL", "SHIFT", "ALT", "GUI")

    private fun register(
        pairs: List<Pair<String, Int>>,
        page: HidPage = HidPage.KEYBOARD,
    ): List<HidKey> = pairs.map { (name, usage) ->
        HidKey(name, usage, page).also { byName[name.uppercase()] = it }
    }
}
