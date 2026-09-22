package dev.hdwatch.buttonmap.ui

import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.Step

/** One-line human summary of a step for pad sector labels and mapping lists. */
fun summarize(step: Step?): String = when (step) {
    null -> "—"
    is Step.TapKey -> {
        val mods = step.mods.sortedBy { it.ordinal }.joinToString("") { modShort(it) }
        if (mods.isEmpty()) step.key else "$mods+${step.key}"
    }
    is Step.KeyDown -> "按住 ${step.key}"
    is Step.KeyUp -> "松开 ${step.key}"
    is Step.TypeText -> "打字「${step.text.take(8)}${if (step.text.length > 8) "…" else ""}」"
    is Step.Wait -> "等 ${step.ms}ms"
    is Step.Mouse -> {
        val parts = mutableListOf<String>()
        if (step.buttons.isNotEmpty()) parts += step.buttons.joinToString("+")
        if (step.dx != 0 || step.dy != 0) parts += "移动(${step.dx},${step.dy})"
        if (step.wheel != 0) parts += if (step.wheel > 0) "轮↑" else "轮↓"
        "鼠标 " + parts.joinToString(" ")
    }
    is Step.MouseClick -> "${mouseName(step.button)}x${step.count}"
    Step.MouseRelease -> "鼠标松开"
    is Step.ConsumerKey -> "媒体 ${step.usage}"
}

fun modShort(mod: KeyMod): String = when (mod) {
    KeyMod.CTRL -> "Ctrl"
    KeyMod.SHIFT -> "Shift"
    KeyMod.ALT -> "Alt"
    KeyMod.GUI -> "Win"
}

fun mouseName(button: String): String = when (button.lowercase()) {
    "left" -> "左键"
    "right" -> "右键"
    "middle" -> "中键"
    "back" -> "后退"
    "forward" -> "前进"
    else -> button
}
