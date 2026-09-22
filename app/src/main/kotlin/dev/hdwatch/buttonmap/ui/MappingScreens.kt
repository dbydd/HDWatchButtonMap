package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.KeyMod
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.hid.HidKey
import dev.hdwatch.buttonmap.hid.HidPage
import dev.hdwatch.buttonmap.hid.KeyCategory
import dev.hdwatch.buttonmap.hid.KeyTable
import dev.hdwatch.buttonmap.input.Symbol

/**
 * 单键映射：列表页每个符号一行（键帽 + 当前动作 + 序列冲突标记），
 * 编辑页选 HID 键 / 修饰键 / 快捷键；全部走玻璃面板语言（HdPanel + 微标签 + 等宽键码）。
 */

@Composable
fun MappingListScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val config by app.configRepo.config.collectAsState()
    val palette = LocalHdPalette.current

    ScreenScaffold(title = stringResource(R.string.scr_map_list_title, config.activeProfile.name)) {
        Symbol.mappable.forEach { sym ->
            val step = config.activeProfile.single[sym]
            // A symbol that starts an enabled multi-symbol sequence never fires
            // its single mapping, so say so instead of showing a dead action.
            // Only macro profiles fire sequences; keys-mode mappings always fire.
            val seqCount = if (config.activeProfile.kind == ProfileKind.MACRO) {
                config.activeProfile.macros.count {
                    it.enabled && it.sequence.size > 1 && it.sequence.first() == sym
                }
            } else {
                0
            }
            SymbolRow(
                symbol = sym,
                action = summarize(step),
                mapped = step != null,
                seqCount = seqCount,
            ) { nav.push(Route.MappingEdit(sym)) }
        }
        Text(
            stringResource(R.string.scr_map_list_footer),
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
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

    var pending by remember(symbol) { mutableStateOf(config.activeProfile.single[symbol]) }
    var mods by remember(symbol) {
        mutableStateOf((config.activeProfile.single[symbol] as? Step.TapKey)?.mods ?: emptySet())
    }
    var openCategory by remember(symbol) { mutableStateOf<KeyCategory?>(null) }

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

    ScreenScaffold(
        title = stringResource(
            R.string.scr_map_edit_title,
            symbol.display,
            stringResource(symbol.labelRes),
            config.activeProfile.name,
        ),
    ) {
        HdPanel(
            modifier = Modifier
                .widthIn(min = 140.dp, max = 176.dp)
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            CornerBrackets(
                color = palette.primary.copy(alpha = 0.55f),
                size = 8.dp,
                stroke = 1.2.dp,
                inset = 2.dp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MicroLabel(stringResource(R.string.scr_map_current_action), palette.primary.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (pending == null) stringResource(R.string.scr_map_none_selected) else summarize(pending),
                    fontFamily = FontFamily.Monospace,
                    color = if (pending == null) palette.muted else palette.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Chip(stringResource(R.string.scr_map_save), active = true) { save() }
            Chip(stringResource(R.string.scr_map_clear), danger = true) { clear() }
        }

        SectionLabel(stringResource(R.string.scr_map_section_shortcuts))
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Chip(stringResource(R.string.scr_map_mouse_left), active = pendingMatches { it is Step.MouseClick && it.button == "left" }) { pending = Step.MouseClick("left") }
            Chip(stringResource(R.string.scr_map_mouse_right), active = pendingMatches { it is Step.MouseClick && it.button == "right" }) { pending = Step.MouseClick("right") }
            Chip(stringResource(R.string.scr_map_mouse_middle), active = pendingMatches { it is Step.MouseClick && it.button == "middle" }) { pending = Step.MouseClick("middle") }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Chip(stringResource(R.string.scr_map_wheel_up), active = pendingMatches { it is Step.Mouse && it.wheel > 0 }) { pending = Step.Mouse(wheel = 1) }
            Chip(stringResource(R.string.scr_map_wheel_down), active = pendingMatches { it is Step.Mouse && it.wheel < 0 }) { pending = Step.Mouse(wheel = -1) }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Chip(stringResource(R.string.scr_map_media_play), active = pendingMatches { it is Step.ConsumerKey && it.usage == "PLAY_PAUSE" }) { pending = Step.ConsumerKey("PLAY_PAUSE") }
            Chip(stringResource(R.string.scr_map_media_vol_up), active = pendingMatches { it is Step.ConsumerKey && it.usage == "VOL_UP" }) { pending = Step.ConsumerKey("VOL_UP") }
            Chip(stringResource(R.string.scr_map_media_vol_down), active = pendingMatches { it is Step.ConsumerKey && it.usage == "VOL_DOWN" }) { pending = Step.ConsumerKey("VOL_DOWN") }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Chip(stringResource(R.string.scr_map_media_mute), active = pendingMatches { it is Step.ConsumerKey && it.usage == "MUTE" }) { pending = Step.ConsumerKey("MUTE") }
            Chip(stringResource(R.string.scr_map_media_next), active = pendingMatches { it is Step.ConsumerKey && it.usage == "NEXT_TRACK" }) { pending = Step.ConsumerKey("NEXT_TRACK") }
            Chip(stringResource(R.string.scr_map_media_prev), active = pendingMatches { it is Step.ConsumerKey && it.usage == "PREV_TRACK" }) { pending = Step.ConsumerKey("PREV_TRACK") }
        }

        SectionLabel(stringResource(R.string.scr_map_section_modifiers))
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyMod.entries.forEach { mod ->
                Chip(
                    label = modShort(mod),
                    active = mod in mods,
                ) { toggleMod(mod) }
            }
        }

        SectionLabel(
            if (category == null) {
                stringResource(R.string.scr_map_section_pick_key)
            } else {
                stringResource(R.string.scr_map_section_pick_key_in, categoryLabel(category))
            },
        )
        if (category == null) {
            KeyTable.categories.forEach { (cat, keys) ->
                HudRow(categoryLabel(cat), stringResource(R.string.scr_map_key_count, keys.size)) { openCategory = cat }
            }
        } else {
            HudRow(stringResource(R.string.scr_map_back_categories)) { openCategory = null }
            categoryKeys.forEach { key ->
                HudRow(
                    label = if (key.name == activeKey) "✓ ${key.name}" else key.name,
                    hint = if (key.page == HidPage.CONSUMER) stringResource(R.string.scr_map_hint_media_page) else "",
                    selected = key.name == activeKey,
                ) { pickKey(key) }
            }
        }

        Spacer(Modifier.height(6.dp))
        HudRow(stringResource(R.string.scr_map_back_no_save)) { nav.pop() }
    }
}

/** Mapping row: keycap, symbol code, bound action, sequence-conflict flag. */
@Composable
private fun SymbolRow(
    symbol: Symbol,
    action: String,
    mapped: Boolean,
    seqCount: Int,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 168.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 11.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(22.dp)
                    .background(
                        Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom)),
                        RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, palette.hairline, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = symbol.display,
                    fontFamily = FontFamily.Monospace,
                    color = if (pressed) palette.onPrimary else palette.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    text = "${symbol.code} " + stringResource(symbol.labelRes),
                    fontFamily = FontFamily.Monospace,
                    color = if (pressed) palette.onPrimary else palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (seqCount > 0) stringResource(R.string.scr_map_seq_conflict, action, seqCount) else action,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        pressed -> palette.onPrimary.copy(alpha = 0.7f)
                        seqCount > 0 -> palette.primary.copy(alpha = 0.9f)
                        mapped -> palette.secondary.copy(alpha = 0.85f)
                        else -> palette.muted
                    },
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Panel row in the menu language, key codes set in console monospace. */
@Composable
private fun HudRow(
    label: String,
    hint: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 168.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 7.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.primary, palette.primary.copy(alpha = 0.15f)),
                        ),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        Column(Modifier.padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                color = when {
                    pressed -> palette.onPrimary
                    selected -> palette.primary
                    else -> palette.text
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.7f) else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact tappable keycap; [active] adds a gold focus rule, never a size change. */
@Composable
private fun Chip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = when {
        pressed -> palette.onPrimary
        active -> palette.primary
        danger -> palette.danger
        else -> palette.text
    }
    HdPanel(
        modifier = modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(7.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
        contentAlignment = Alignment.Center,
    ) {
        if (active && !pressed) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, palette.primary, Color.Transparent),
                        ),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
        )
    }
}

/** Picker category label: stable enum id in, localized text out. */
@Composable
private fun categoryLabel(category: KeyCategory): String = stringResource(
    when (category) {
        KeyCategory.LETTERS -> R.string.hid_cat_letters
        KeyCategory.DIGITS -> R.string.hid_cat_digits
        KeyCategory.EDITING -> R.string.hid_cat_editing
        KeyCategory.ARROWS -> R.string.hid_cat_arrows
        KeyCategory.PUNCTUATION -> R.string.hid_cat_punctuation
        KeyCategory.FUNCTION -> R.string.hid_cat_function
        KeyCategory.NUMPAD -> R.string.hid_cat_numpad
        KeyCategory.MODIFIERS -> R.string.hid_cat_modifiers
        KeyCategory.MEDIA -> R.string.hid_cat_media
    },
)
