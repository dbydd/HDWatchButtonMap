package dev.hdwatch.buttonmap.config

import android.content.Context
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.input.Symbol

/**
 * Ready-to-use profile templates users can add directly on the watch without
 * hand-editing JSON (Presentation / Media remote, Mouse & Nav, Desktop Hotkeys).
 */
object ProfilePresets {

    enum class PresetId { MEDIA_SLIDES, MOUSE_NAV, DESKTOP_HOTKEYS }

    data class PresetInfo(
        val id: PresetId,
        val profileId: String,
        val nameRes: Int,
        val hintRes: Int,
    )

    val all: List<PresetInfo> = listOf(
        PresetInfo(
            id = PresetId.MEDIA_SLIDES,
            profileId = "preset_media",
            nameRes = R.string.app_preset_media,
            hintRes = R.string.app_preset_media_hint,
        ),
        PresetInfo(
            id = PresetId.MOUSE_NAV,
            profileId = "preset_mouse",
            nameRes = R.string.app_preset_mouse,
            hintRes = R.string.app_preset_mouse_hint,
        ),
        PresetInfo(
            id = PresetId.DESKTOP_HOTKEYS,
            profileId = "preset_hotkeys",
            nameRes = R.string.app_preset_hotkeys,
            hintRes = R.string.app_preset_hotkeys_hint,
        ),
    )

    fun build(context: Context, id: PresetId): Profile = when (id) {
        PresetId.MEDIA_SLIDES -> Profile(
            id = "preset_media",
            name = context.getString(R.string.app_preset_media),
            kind = ProfileKind.KEYS,
            rotateThreshold = 1,
            single = linkedMapOf(
                Symbol.LEFT to Step.TapKey("LEFT"),
                Symbol.RIGHT to Step.TapKey("RIGHT"),
                Symbol.UP to Step.TapKey("F5"),
                Symbol.DOWN to Step.TapKey("ESC"),
                Symbol.CROWN_CW to Step.ConsumerKey("VOL_UP"),
                Symbol.CROWN_CCW to Step.ConsumerKey("VOL_DOWN"),
                Symbol.STEM to Step.ConsumerKey("PLAY_PAUSE"),
                Symbol.STEM_LONG to Step.ConsumerKey("MUTE"),
                Symbol.GESTURE_1 to Step.ConsumerKey("PREV_TRACK"),
                Symbol.GESTURE_2 to Step.ConsumerKey("NEXT_TRACK"),
            ),
        )

        PresetId.MOUSE_NAV -> Profile(
            id = "preset_mouse",
            name = context.getString(R.string.app_preset_mouse),
            kind = ProfileKind.KEYS,
            rotateThreshold = 1,
            single = linkedMapOf(
                Symbol.UP to Step.TapKey("PAGE_UP"),
                Symbol.DOWN to Step.TapKey("PAGE_DOWN"),
                Symbol.LEFT to Step.MouseClick("back"),
                Symbol.RIGHT to Step.MouseClick("forward"),
                Symbol.CROWN_CW to Step.Mouse(wheel = -1),
                Symbol.CROWN_CCW to Step.Mouse(wheel = 1),
                Symbol.STEM to Step.MouseClick("left"),
                Symbol.STEM_LONG to Step.MouseClick("right"),
                Symbol.GESTURE_1 to Step.MouseClick("middle"),
                Symbol.GESTURE_2 to Step.TapKey("TAB", setOf(KeyMod.ALT)),
            ),
        )

        PresetId.DESKTOP_HOTKEYS -> Profile(
            id = "preset_hotkeys",
            name = context.getString(R.string.app_preset_hotkeys),
            kind = ProfileKind.MACRO,
            rotateThreshold = 2,
            single = linkedMapOf(
                Symbol.CROWN_CW to Step.ConsumerKey("VOL_UP"),
                Symbol.CROWN_CCW to Step.ConsumerKey("VOL_DOWN"),
                Symbol.STEM to Step.ConsumerKey("PLAY_PAUSE"),
            ),
            macros = listOf(
                Macro(
                    id = "hk_copy",
                    name = context.getString(R.string.app_macro_copy),
                    sequence = listOf(Symbol.UP, Symbol.UP),
                    steps = listOf(Step.TapKey("C", setOf(KeyMod.CTRL))),
                ),
                Macro(
                    id = "hk_paste",
                    name = context.getString(R.string.app_macro_paste),
                    sequence = listOf(Symbol.DOWN, Symbol.DOWN),
                    steps = listOf(Step.TapKey("V", setOf(KeyMod.CTRL))),
                ),
                Macro(
                    id = "hk_undo",
                    name = context.getString(R.string.app_macro_undo),
                    sequence = listOf(Symbol.LEFT, Symbol.LEFT),
                    steps = listOf(Step.TapKey("Z", setOf(KeyMod.CTRL))),
                ),
                Macro(
                    id = "hk_redo",
                    name = context.getString(R.string.app_macro_redo),
                    sequence = listOf(Symbol.RIGHT, Symbol.RIGHT),
                    steps = listOf(Step.TapKey("Y", setOf(KeyMod.CTRL))),
                ),
                Macro(
                    id = "hk_screenshot",
                    name = context.getString(R.string.app_macro_screenshot),
                    sequence = listOf(Symbol.UP, Symbol.RIGHT),
                    steps = listOf(Step.TapKey("S", setOf(KeyMod.GUI, KeyMod.SHIFT))),
                ),
                Macro(
                    id = "hk_lock",
                    name = context.getString(R.string.app_macro_lock),
                    sequence = listOf(Symbol.DOWN, Symbol.LEFT),
                    steps = listOf(Step.TapKey("L", setOf(KeyMod.GUI))),
                ),
            ),
        )
    }
}
