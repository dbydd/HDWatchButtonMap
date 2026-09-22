package dev.hdwatch.buttonmap.input

/**
 * Normalized input symbol. Every raw input (screen sector tap, stem key,
 * crown/bezel rotation, gesture sensor) collapses into one of these before
 * reaching the sequence engine. `code` is the stable id used inside config
 * files; `glyph` is for on-screen display (null = render the code text);
 * `labelRes` is the localized name shown by the mapping editor.
 */
enum class Symbol(
    val code: String,
    val glyph: String?,
    @androidx.annotation.StringRes val labelRes: Int,
) {
    UP("U", "▲", dev.hdwatch.buttonmap.R.string.sym_up),
    RIGHT("R", "▶", dev.hdwatch.buttonmap.R.string.sym_right),
    DOWN("D", "▼", dev.hdwatch.buttonmap.R.string.sym_down),
    LEFT("L", "◀", dev.hdwatch.buttonmap.R.string.sym_left),
    CROWN_CW("CW", "↻", dev.hdwatch.buttonmap.R.string.sym_cw),
    CROWN_CCW("CCW", "↺", dev.hdwatch.buttonmap.R.string.sym_ccw),
    STEM("S", null, dev.hdwatch.buttonmap.R.string.sym_stem),
    STEM_LONG("SL", null, dev.hdwatch.buttonmap.R.string.sym_stem_long),
    GESTURE_1("G1", null, dev.hdwatch.buttonmap.R.string.sym_g1),
    GESTURE_2("G2", null, dev.hdwatch.buttonmap.R.string.sym_g2),
    GESTURE_3("G3", null, dev.hdwatch.buttonmap.R.string.sym_g3),
    GESTURE_4("G4", null, dev.hdwatch.buttonmap.R.string.sym_g4);

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
