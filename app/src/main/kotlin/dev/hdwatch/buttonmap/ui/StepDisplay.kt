package dev.hdwatch.buttonmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.Step

/**
 * One-line human summary of a step for pad sector labels and mapping lists.
 * Composable: every word of it is a pad string resource.
 */
@Composable
fun summarize(step: Step?): String = when (step) {
    null -> "—"
    is Step.TapKey -> {
        val mods = step.mods.sortedBy { it.ordinal }.joinToString("") { modShort(it) }
        if (mods.isEmpty()) step.key else "$mods+${step.key}"
    }
    is Step.KeyDown -> stringResource(R.string.pad_step_hold_key, step.key)
    is Step.KeyUp -> stringResource(R.string.pad_step_release_key, step.key)
    is Step.Hold -> {
        val mods = step.mods.sortedBy { it.ordinal }.joinToString("") { modShort(it) }
        if (mods.isEmpty()) {
            stringResource(R.string.pad_step_latch_key, step.key)
        } else {
            stringResource(R.string.pad_step_latch_combo, mods, step.key)
        }
    }
    is Step.TypeText -> {
        val shown = step.text.take(8) + if (step.text.length > 8) "…" else ""
        stringResource(R.string.pad_step_type_text, shown)
    }
    is Step.Wait -> stringResource(R.string.pad_step_wait, step.ms)
    is Step.Mouse -> {
        val parts = mutableListOf<String>()
        if (step.buttons.isNotEmpty()) parts += step.buttons.joinToString("+")
        if (step.dx != 0 || step.dy != 0) {
            parts += stringResource(R.string.pad_step_mouse_move, step.dx, step.dy)
        }
        if (step.wheel != 0) {
            parts += stringResource(
                if (step.wheel > 0) R.string.pad_step_wheel_up else R.string.pad_step_wheel_down,
            )
        }
        stringResource(R.string.pad_step_mouse) + " " + parts.joinToString(" ")
    }
    is Step.MouseClick -> {
        val name = mouseName(step.button)
        stringResource(R.string.pad_step_click, name, step.count)
    }
    Step.MouseRelease -> stringResource(R.string.pad_step_mouse_release)
    is Step.ConsumerKey -> stringResource(R.string.pad_step_media, step.usage)
}

/** Modifier prefix of a step summary — HID key names, never translated. */
fun modShort(mod: KeyMod): String = when (mod) {
    KeyMod.CTRL -> "Ctrl"
    KeyMod.SHIFT -> "Shift"
    KeyMod.ALT -> "Alt"
    KeyMod.GUI -> "Win"
}

/** Localized name of a mouse button; unknown buttons pass through as data. */
@Composable
fun mouseName(button: String): String = when (button.lowercase()) {
    "left" -> stringResource(R.string.pad_mouse_left)
    "right" -> stringResource(R.string.pad_mouse_right)
    "middle" -> stringResource(R.string.pad_mouse_middle)
    "back" -> stringResource(R.string.pad_mouse_back)
    "forward" -> stringResource(R.string.pad_mouse_forward)
    else -> button
}
