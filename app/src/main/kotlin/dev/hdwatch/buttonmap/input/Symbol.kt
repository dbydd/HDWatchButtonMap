package dev.hdwatch.buttonmap.input

/**
 * Normalized input symbol. Every raw input (screen sector tap, stem key,
 * crown/bezel rotation, gesture sensor) collapses into one of these before
 * reaching the sequence engine. `code` is the stable id used inside config
 * files; `glyph` is for on-screen display (null = render the code text).
 */
enum class Symbol(val code: String, val glyph: String?, val label: String) {
    UP("U", "▲", "上"),
    RIGHT("R", "▶", "右"),
    DOWN("D", "▼", "下"),
    LEFT("L", "◀", "左"),
    CROWN_CW("CW", "↻", "顺转"),
    CROWN_CCW("CCW", "↺", "逆转"),
    STEM("S", null, "按键"),
    STEM_LONG("SL", null, "长按键"),
    GESTURE_1("G1", null, "手势1"),
    GESTURE_2("G2", null, "手势2"),
    GESTURE_3("G3", null, "手势3"),
    GESTURE_4("G4", null, "手势4");

    val display: String get() = glyph ?: code

    companion object {
        fun fromCode(raw: String): Symbol? =
            entries.firstOrNull { it.code.equals(raw.trim(), ignoreCase = true) }

        /** Order used by the on-watch mapping editor. */
        val mappable: List<Symbol> = listOf(
            UP, DOWN, LEFT, RIGHT, CROWN_CW, CROWN_CCW, STEM, STEM_LONG,
            GESTURE_1, GESTURE_2, GESTURE_3, GESTURE_4,
        )
    }
}
