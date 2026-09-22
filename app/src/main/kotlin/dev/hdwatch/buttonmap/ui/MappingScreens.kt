package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.hid.HidKey
import dev.hdwatch.buttonmap.hid.HidPage
import dev.hdwatch.buttonmap.hid.KeyTable
import dev.hdwatch.buttonmap.input.Symbol

/** 单键映射：列表页显示每个输入符号的当前动作与宏序列冲突，编辑页选择 HID 键/修饰键/快捷键。 */

@Composable
fun MappingListScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val config by app.configRepo.config.collectAsState()
    val palette = LocalHdPalette.current

    ScreenScaffold(title = "单键映射") {
        Symbol.mappable.forEach { sym ->
            val step = config.single[sym]
            // A symbol that starts an enabled multi-symbol sequence never fires
            // its single mapping, so say so instead of showing a dead action.
            val seqCount = config.macros.count {
                it.enabled && it.sequence.size > 1 && it.sequence.first() == sym
            }
            val hint = if (seqCount > 0) "${summarize(step)} · 序列中×$seqCount" else summarize(step)
            MenuRow(
                label = "${sym.display} ${sym.label}",
                hint = hint,
            ) { nav.push(Route.MappingEdit(sym)) }
        }
        Text(
            "未启用序列前缀的按键立即触发；完整编辑请用外部 hdmap.json",
            color = palette.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun MappingEditScreen(symbol: Symbol) {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val config by app.configRepo.config.collectAsState()
    val palette = LocalHdPalette.current

    var pending by remember(symbol) { mutableStateOf(config.single[symbol]) }
    var mods by remember(symbol) {
        mutableStateOf((config.single[symbol] as? Step.TapKey)?.mods ?: emptySet())
    }
    var openCategory by remember(symbol) { mutableStateOf<String?>(null) }

    fun pickKey(key: HidKey) {
        pending = if (key.page == HidPage.CONSUMER) {
            Step.ConsumerKey(key.name)
        } else {
            Step.TapKey(key.name, mods)
        }
    }

    fun toggleMod(mod: KeyMod) {
        mods = if (mod in mods) mods - mod else mods + mod
        val tap = pending as? Step.TapKey
        if (tap != null) pending = tap.copy(mods = mods)
    }

    fun save() {
        app.configRepo.setSingle(symbol, pending)
        nav.pop()
    }

    fun clear() {
        app.configRepo.setSingle(symbol, null)
        nav.pop()
    }

    /** Quick chips light up when [pending] is the step they would write. */
    fun pendingMatches(match: (Step) -> Boolean): Boolean {
        val current = pending
        return current != null && match(current)
    }

    val activeKey = (pending as? Step.TapKey)?.key ?: (pending as? Step.ConsumerKey)?.usage
    val category = openCategory
    val categoryKeys = KeyTable.categories.firstOrNull { it.first == category }?.second.orEmpty()

    ScreenScaffold(title = "映射：${symbol.display} ${symbol.label}") {
        Column(
            modifier = Modifier
                .background(palette.surface, RoundedCornerShape(9.dp))
                .border(1.dp, palette.primary.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("当前动作", color = palette.muted, fontSize = 10.sp)
            Text(
                if (pending == null) "未选择" else summarize(pending),
                color = palette.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("保存", active = true) { save() }
            Chip("清除", danger = true) { clear() }
        }

        MappingSectionLabel("快捷键")
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("左键", active = pendingMatches { it is Step.MouseClick && it.button == "left" }) { pending = Step.MouseClick("left") }
            Chip("右键", active = pendingMatches { it is Step.MouseClick && it.button == "right" }) { pending = Step.MouseClick("right") }
            Chip("中键", active = pendingMatches { it is Step.MouseClick && it.button == "middle" }) { pending = Step.MouseClick("middle") }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("滚轮↑", active = pendingMatches { it is Step.Mouse && it.wheel > 0 }) { pending = Step.Mouse(wheel = 1) }
            Chip("滚轮↓", active = pendingMatches { it is Step.Mouse && it.wheel < 0 }) { pending = Step.Mouse(wheel = -1) }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("播放", active = pendingMatches { it is Step.ConsumerKey && it.usage == "PLAY_PAUSE" }) { pending = Step.ConsumerKey("PLAY_PAUSE") }
            Chip("音量+", active = pendingMatches { it is Step.ConsumerKey && it.usage == "VOL_UP" }) { pending = Step.ConsumerKey("VOL_UP") }
            Chip("音量-", active = pendingMatches { it is Step.ConsumerKey && it.usage == "VOL_DOWN" }) { pending = Step.ConsumerKey("VOL_DOWN") }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip("静音", active = pendingMatches { it is Step.ConsumerKey && it.usage == "MUTE" }) { pending = Step.ConsumerKey("MUTE") }
            Chip("下一曲", active = pendingMatches { it is Step.ConsumerKey && it.usage == "NEXT_TRACK" }) { pending = Step.ConsumerKey("NEXT_TRACK") }
            Chip("上一曲", active = pendingMatches { it is Step.ConsumerKey && it.usage == "PREV_TRACK" }) { pending = Step.ConsumerKey("PREV_TRACK") }
        }

        MappingSectionLabel("修饰键")
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyMod.entries.forEach { mod ->
                Chip(
                    label = modShort(mod),
                    active = mod in mods,
                ) { toggleMod(mod) }
            }
        }

        MappingSectionLabel(if (category == null) "选择键 · 先选类别" else "选择键 · $category")
        if (category == null) {
            KeyTable.categories.forEach { (name, keys) ->
                MenuRow(name, "${keys.size} 个键") { openCategory = name }
            }
        } else {
            MenuRow("← 返回类别") { openCategory = null }
            categoryKeys.forEach { key ->
                MenuRow(
                    label = if (key.name == activeKey) "✓ ${key.name}" else key.name,
                    hint = if (key.page == HidPage.CONSUMER) "媒体页" else "",
                ) { pickKey(key) }
            }
        }

        Spacer(Modifier.height(8.dp))
        MenuRow("← 返回（不保存）") { nav.pop() }
    }
}

@Composable
private fun MappingSectionLabel(text: String) {
    val palette = LocalHdPalette.current
    Text(
        text = text,
        color = palette.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    )
}

/** Compact tappable label; [active]/[danger] only change colors, never size. */
@Composable
private fun Chip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val background = when {
        active -> palette.primary
        danger -> palette.danger.copy(alpha = 0.18f)
        else -> palette.surface
    }
    val foreground = when {
        active -> palette.onPrimary
        danger -> palette.danger
        else -> palette.text
    }
    Box(
        modifier = modifier
            .height(40.dp)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
