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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
 * Key mapping screens:
 *  - [MappingListScreen]: 12 dial inputs grouped cleanly into Dial Keycaps,
 *    Crown & Stem, and Slots & Gestures.
 *  - [MappingEditScreen]: Hero preview + Save/Test/Clear bar + 4-tab action
 *    studio (Key / Hold / Mouse / Media) with compact 4-col & 2-col tactile
 *    keycap grids so binding any key takes only a few taps on a wrist.
 */

private enum class EditTab { KEY, HOLD, MOUSE, MEDIA }

@Composable
fun MappingListScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val palette = LocalHdPalette.current
    val profile = config.activeProfile

    val groups = listOf(
        R.string.scr_map_group_dial to listOf(Symbol.UP, Symbol.DOWN, Symbol.LEFT, Symbol.RIGHT),
        R.string.scr_map_group_crown to listOf(Symbol.CROWN_CW, Symbol.CROWN_CCW, Symbol.STEM, Symbol.STEM_LONG),
        R.string.scr_map_group_slots to listOf(Symbol.GESTURE_1, Symbol.GESTURE_2, Symbol.GESTURE_3, Symbol.GESTURE_4),
    )

    ScreenScaffold(title = stringResource(R.string.scr_map_list_title, profile.name)) {
        groups.forEach { (headerRes, symbols) ->
            SectionLabel(stringResource(headerRes))
            symbols.forEach { sym ->
                val step = profile.single[sym]
                val seqCount = if (profile.kind == ProfileKind.MACRO) {
                    profile.macros.count {
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
                ) {
                    haptics.tick()
                    nav.push(Route.MappingEdit(sym))
                }
            }
        }
        Text(
            text = stringResource(R.string.scr_map_list_footer),
            color = palette.muted,
            fontSize = 8.5.sp,
            maxLines = 2,
            modifier = Modifier
                .settingsCardWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
fun MappingEditScreen(symbol: Symbol) {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val palette = LocalHdPalette.current

    val savedStep = config.activeProfile.single[symbol]
    var pending by remember(symbol) { mutableStateOf(savedStep) }
    var mods by remember(symbol) {
        mutableStateOf(
            when (savedStep) {
                is Step.TapKey -> savedStep.mods
                is Step.Hold -> savedStep.mods
                else -> emptySet()
            },
        )
    }
    var clickCount by remember(symbol) {
        mutableStateOf((savedStep as? Step.MouseClick)?.count?.coerceIn(1, 2) ?: 1)
    }
    var activeTab by remember(symbol) {
        mutableStateOf(
            when (savedStep) {
                is Step.Hold -> EditTab.HOLD
                is Step.Mouse, is Step.MouseClick, Step.MouseRelease -> EditTab.MOUSE
                is Step.ConsumerKey -> EditTab.MEDIA
                else -> EditTab.KEY
            },
        )
    }
    var openCategory by remember(symbol) { mutableStateOf<KeyCategory?>(null) }

    val isDirty = pending != savedStep

    fun selectKey(keyName: String, asHold: Boolean) {
        haptics.tick()
        val resolved = KeyTable.resolve(keyName)
        pending = if (resolved?.page == HidPage.CONSUMER) {
            Step.ConsumerKey(resolved.name)
        } else if (asHold) {
            Step.Hold(resolved?.name ?: keyName, mods)
        } else {
            Step.TapKey(resolved?.name ?: keyName, mods)
        }
    }

    fun toggleMod(mod: KeyMod) {
        haptics.tick()
        mods = if (mod in mods) mods - mod else mods + mod
        when (val cur = pending) {
            is Step.TapKey -> pending = cur.copy(mods = mods)
            is Step.Hold -> pending = cur.copy(mods = mods)
            else -> Unit
        }
    }

    fun save() {
        haptics.tick()
        app.configRepo.setSingle(symbol, pending)
        nav.pop()
    }

    fun clear() {
        haptics.tick()
        app.configRepo.setSingle(symbol, null)
        nav.pop()
    }

    fun testFire() {
        val step = pending ?: return
        haptics.single()
        app.runner.runStep(step)
    }

    val activeKeyName = when (val cur = pending) {
        is Step.TapKey -> cur.key
        is Step.Hold -> cur.key
        is Step.ConsumerKey -> cur.usage
        else -> null
    }
    val keyboardCategories = remember {
        KeyTable.categories.filter { it.first != KeyCategory.MEDIA }
    }

    ScreenScaffold(
        title = stringResource(
            R.string.scr_map_edit_title,
            symbol.display,
            stringResource(symbol.labelRes),
            config.activeProfile.name,
        ),
    ) {
        // ---- Hero action preview card ----
        HdPanel(
            modifier = Modifier
                .settingsCardWidth()
                .padding(vertical = 2.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            CornerBrackets(
                color = palette.primary.copy(alpha = if (isDirty) 0.85f else 0.45f),
                size = 7.dp,
                stroke = 1.2.dp,
                inset = 2.dp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    MicroLabel(
                        text = "${symbol.display} ${symbol.code}",
                        color = palette.primary.copy(alpha = 0.78f),
                    )
                    if (isDirty) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .background(palette.primary, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (pending == null) stringResource(R.string.scr_map_none_selected) else summarize(pending),
                    fontFamily = FontFamily.Monospace,
                    color = if (pending == null) palette.muted else palette.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---- Aligned Save / Test / Clear bar ----
        Row(
            modifier = Modifier
                .settingsCardWidth()
                .padding(top = 4.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ActionButton(
                text = stringResource(R.string.scr_map_save),
                tone = HdTone.Accent,
                modifier = Modifier.weight(1f),
                onClick = { save() },
            )
            ActionButton(
                text = stringResource(R.string.scr_map_test),
                enabled = pending != null,
                modifier = Modifier.weight(1f),
                onClick = { testFire() },
            )
            ActionButton(
                text = stringResource(R.string.scr_map_clear),
                tone = HdTone.Danger,
                enabled = savedStep != null || pending != null,
                modifier = Modifier.weight(1f),
                onClick = { clear() },
            )
        }

        // ---- 4-tab action type dock ----
        EditTabBar(
            activeTab = activeTab,
            onSelect = { tab ->
                haptics.tick()
                activeTab = tab
                openCategory = null
                // Convert seamlessly between TapKey and Hold when flipping Key <-> Hold.
                val cur = pending
                if (tab == EditTab.HOLD && cur is Step.TapKey) {
                    pending = Step.Hold(cur.key, cur.mods)
                } else if (tab == EditTab.KEY && cur is Step.Hold) {
                    pending = Step.TapKey(cur.key, cur.mods)
                }
            },
        )

        when (activeTab) {
            EditTab.KEY -> {
                ModifierBar(mods = mods, onToggle = ::toggleMod)

                if (openCategory == null) {
                    SectionLabel(stringResource(R.string.scr_map_section_quick_keys))
                    KeycapGrid(
                        items = listOf(
                            "W" to "W", "A" to "A", "S" to "S", "D" to "D",
                            "SPC" to "SPACE", "ENT" to "ENTER", "ESC" to "ESC", "TAB" to "TAB",
                        ),
                        columns = 4,
                        activeValue = activeKeyName?.takeIf { pending is Step.TapKey },
                        onPick = { selectKey(it, asHold = false) },
                    )
                    SectionLabel(stringResource(R.string.scr_map_section_pick_key))
                    CategoryGrid(
                        categories = keyboardCategories,
                        onOpen = {
                            haptics.tick()
                            openCategory = it
                        },
                    )
                } else {
                    val cat = openCategory!!
                    val keys = keyboardCategories.firstOrNull { it.first == cat }?.second.orEmpty()
                    CategoryKeycapBrowser(
                        category = cat,
                        keys = keys,
                        activeKeyName = activeKeyName?.takeIf { pending is Step.TapKey },
                        onBack = {
                            haptics.tick()
                            openCategory = null
                        },
                        onPick = { selectKey(it.name, asHold = false) },
                        onSave = { save() },
                    )
                }
            }

            EditTab.HOLD -> {
                Text(
                    text = stringResource(R.string.scr_map_hold_hint),
                    color = palette.muted,
                    fontSize = 8.5.sp,
                    maxLines = 2,
                    modifier = Modifier
                        .settingsCardWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                )
                SectionLabel(stringResource(R.string.scr_map_section_hold_presets))
                KeycapGrid(
                    items = listOf(
                        "CTRL" to "CTRL_L",
                        "SHIFT" to "SHIFT_L",
                        "ALT" to "ALT_L",
                        "WIN" to "GUI_L",
                        "SPC" to "SPACE",
                        "W" to "W",
                    ),
                    columns = 3,
                    activeValue = (pending as? Step.Hold)?.let { KeyTable.resolve(it.key)?.name ?: it.key },
                    onPick = { keyName ->
                        haptics.tick()
                        pending = Step.Hold(keyName, emptySet())
                        mods = emptySet()
                    },
                )
                ModifierBar(mods = mods, onToggle = ::toggleMod)
                if (openCategory == null) {
                    SectionLabel(stringResource(R.string.scr_map_section_more_keys))
                    CategoryGrid(
                        categories = keyboardCategories,
                        onOpen = {
                            haptics.tick()
                            openCategory = it
                        },
                    )
                } else {
                    val cat = openCategory!!
                    val keys = keyboardCategories.firstOrNull { it.first == cat }?.second.orEmpty()
                    CategoryKeycapBrowser(
                        category = cat,
                        keys = keys,
                        activeKeyName = activeKeyName?.takeIf { pending is Step.Hold },
                        onBack = {
                            haptics.tick()
                            openCategory = null
                        },
                        onPick = { selectKey(it.name, asHold = true) },
                        onSave = { save() },
                    )
                }
            }

            EditTab.MOUSE -> {
                SectionLabel(stringResource(R.string.scr_map_section_mouse_btn))
                KeycapGrid(
                    items = listOf(
                        stringResource(R.string.scr_map_mouse_left) to "left",
                        stringResource(R.string.scr_map_mouse_right) to "right",
                        stringResource(R.string.scr_map_mouse_middle) to "middle",
                    ),
                    columns = 3,
                    activeValue = (pending as? Step.MouseClick)?.button?.lowercase(),
                    onPick = { btn ->
                        haptics.tick()
                        pending = Step.MouseClick(btn, clickCount)
                    },
                )
                KeycapGrid(
                    items = listOf(
                        stringResource(R.string.scr_map_mouse_back) to "back",
                        stringResource(R.string.scr_map_mouse_forward) to "forward",
                    ),
                    columns = 2,
                    activeValue = (pending as? Step.MouseClick)?.button?.lowercase(),
                    onPick = { btn ->
                        haptics.tick()
                        pending = Step.MouseClick(btn, clickCount)
                    },
                )
                Row(
                    modifier = Modifier
                        .settingsCardWidth()
                        .padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    KeycapChip(
                        label = stringResource(R.string.scr_map_click_single),
                        active = clickCount == 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        haptics.tick()
                        clickCount = 1
                        (pending as? Step.MouseClick)?.let { pending = it.copy(count = 1) }
                    }
                    KeycapChip(
                        label = stringResource(R.string.scr_map_click_double),
                        active = clickCount == 2,
                        modifier = Modifier.weight(1f),
                    ) {
                        haptics.tick()
                        clickCount = 2
                        (pending as? Step.MouseClick)?.let { pending = it.copy(count = 2) }
                    }
                }

                SectionLabel(stringResource(R.string.scr_map_section_mouse_wheel))
                val wheelVal = (pending as? Step.Mouse)?.wheel
                Row(
                    modifier = Modifier
                        .settingsCardWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    KeycapChip(
                        label = stringResource(R.string.scr_map_wheel_up),
                        active = wheelVal == 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        haptics.tick()
                        pending = Step.Mouse(wheel = 1)
                    }
                    KeycapChip(
                        label = stringResource(R.string.scr_map_wheel_down),
                        active = wheelVal == -1,
                        modifier = Modifier.weight(1f),
                    ) {
                        haptics.tick()
                        pending = Step.Mouse(wheel = -1)
                    }
                }
                Row(
                    modifier = Modifier
                        .settingsCardWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    KeycapChip(
                        label = stringResource(R.string.scr_map_wheel_up_fast),
                        active = wheelVal == 3,
                        modifier = Modifier.weight(1f),
                    ) {
                        haptics.tick()
                        pending = Step.Mouse(wheel = 3)
                    }
                    KeycapChip(
                        label = stringResource(R.string.scr_map_wheel_down_fast),
                        active = wheelVal == -3,
                        modifier = Modifier.weight(1f),
                    ) {
                        haptics.tick()
                        pending = Step.Mouse(wheel = -3)
                    }
                }
            }

            EditTab.MEDIA -> {
                SectionLabel(stringResource(R.string.scr_map_section_media))
                KeycapGrid(
                    items = listOf(
                        stringResource(R.string.scr_map_media_play) to "PLAY_PAUSE",
                        stringResource(R.string.scr_map_media_mute) to "MUTE",
                        stringResource(R.string.scr_map_media_vol_up) to "VOL_UP",
                        stringResource(R.string.scr_map_media_vol_down) to "VOL_DOWN",
                        stringResource(R.string.scr_map_media_prev) to "PREV_TRACK",
                        stringResource(R.string.scr_map_media_next) to "NEXT_TRACK",
                        stringResource(R.string.scr_map_media_stop) to "STOP",
                        stringResource(R.string.scr_map_media_bright_up) to "BRIGHTNESS_UP",
                        stringResource(R.string.scr_map_media_bright_down) to "BRIGHTNESS_DOWN",
                        stringResource(R.string.scr_map_media_home) to "AC_HOME",
                        stringResource(R.string.scr_map_media_back) to "AC_BACK",
                    ),
                    columns = 2,
                    activeValue = (pending as? Step.ConsumerKey)?.usage,
                    onPick = { usage ->
                        haptics.tick()
                        pending = Step.ConsumerKey(usage)
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Sub-components

@Composable
private fun EditTabBar(
    activeTab: EditTab,
    onSelect: (EditTab) -> Unit,
) {
    val palette = LocalHdPalette.current
    val tabs = listOf(
        EditTab.KEY to stringResource(R.string.scr_map_tab_key),
        EditTab.HOLD to stringResource(R.string.scr_map_tab_hold),
        EditTab.MOUSE to stringResource(R.string.scr_map_tab_mouse),
        EditTab.MEDIA to stringResource(R.string.scr_map_tab_media),
    )
    Row(
        modifier = Modifier
            .settingsCardWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (tab, label) ->
            val selected = tab == activeTab
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            HdPanel(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                shape = RoundedCornerShape(7.dp),
                pressed = pressed,
                interactionSource = interaction,
                onClick = { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected && !pressed) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
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
                    color = when {
                        pressed -> palette.onPrimary
                        selected -> palette.primary
                        else -> palette.muted
                    },
                    fontSize = 9.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ModifierBar(
    mods: Set<KeyMod>,
    onToggle: (KeyMod) -> Unit,
) {
    SectionLabel(stringResource(R.string.scr_map_section_modifiers))
    Row(
        modifier = Modifier
            .settingsCardWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyMod.entries.forEach { mod ->
            KeycapChip(
                label = modShort(mod),
                active = mod in mods,
                modifier = Modifier.weight(1f),
            ) { onToggle(mod) }
        }
    }
}

@Composable
private fun CategoryGrid(
    categories: List<Pair<KeyCategory, List<HidKey>>>,
    onOpen: (KeyCategory) -> Unit,
) {
    val palette = LocalHdPalette.current
    categories.chunked(2).forEach { row ->
        Row(
            modifier = Modifier
                .settingsCardWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            row.forEach { (cat, keys) ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                HdPanel(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp),
                    shape = RoundedCornerShape(7.dp),
                    pressed = pressed,
                    interactionSource = interaction,
                    onClick = { onOpen(cat) },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = categoryLabel(cat),
                            color = if (pressed) palette.onPrimary else palette.text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.scr_map_key_count, keys.size),
                            fontFamily = FontFamily.Monospace,
                            color = if (pressed) palette.onPrimary.copy(alpha = 0.72f) else palette.muted,
                            fontSize = 8.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CategoryKeycapBrowser(
    category: KeyCategory,
    keys: List<HidKey>,
    activeKeyName: String?,
    onBack: () -> Unit,
    onPick: (HidKey) -> Unit,
    onSave: () -> Unit,
) {
    SectionLabel(stringResource(R.string.scr_map_section_pick_key_in, categoryLabel(category)))
    ActionButton(
        text = stringResource(R.string.scr_map_back_categories),
        modifier = Modifier
            .settingsCardWidth()
            .padding(bottom = 4.dp),
        onClick = onBack,
    )
    val cols = when (category) {
        KeyCategory.LETTERS, KeyCategory.DIGITS, KeyCategory.ARROWS, KeyCategory.FUNCTION -> 4
        else -> 2
    }
    KeycapGrid(
        items = keys.map { keycapShortLabel(it.name) to it.name },
        columns = cols,
        activeValue = activeKeyName,
        onPick = { name ->
            keys.firstOrNull { it.name == name }?.let(onPick)
        },
    )
    Spacer(Modifier.height(5.dp))
    ActionButton(
        text = stringResource(R.string.scr_map_save),
        tone = HdTone.Accent,
        modifier = Modifier.settingsCardWidth(),
        onClick = onSave,
    )
}

@Composable
private fun KeycapGrid(
    items: List<Pair<String, String>>,
    columns: Int,
    activeValue: String?,
    onPick: (String) -> Unit,
) {
    items.chunked(columns).forEach { row ->
        Row(
            modifier = Modifier
                .settingsCardWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.forEach { (label, value) ->
                KeycapChip(
                    label = label,
                    active = activeValue != null && activeValue.equals(value, ignoreCase = true),
                    modifier = Modifier.weight(1f),
                ) { onPick(value) }
            }
            repeat(columns - row.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Tactile keycap button with bottom gold focus rule when active. */
@Composable
private fun KeycapChip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = modifier.height(33.dp),
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
            color = when {
                pressed -> palette.onPrimary
                active -> palette.primary
                else -> palette.text
            },
            fontSize = if (label.length > 6) 8.5.sp else 9.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}

/** Mapping list row: keycap badge, symbol code, bound action, sequence-conflict flag. */
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
            .settingsCardWidth()
            .padding(vertical = 2.5.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
    ) {
        if ((mapped || seqCount > 0) && !pressed) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(25.dp)
                    .height(22.dp)
                    .background(
                        if (pressed) {
                            Brush.verticalGradient(listOf(palette.goldDeep, palette.goldDeep))
                        } else {
                            Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom))
                        },
                        RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, palette.hairline, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = symbol.display,
                    fontFamily = FontFamily.Monospace,
                    color = if (pressed) palette.text else palette.primary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp),
            ) {
                Text(
                    text = "${symbol.code} " + stringResource(symbol.labelRes),
                    fontFamily = FontFamily.Monospace,
                    color = if (pressed) palette.onPrimary else palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (seqCount > 0) stringResource(R.string.scr_map_seq_conflict, action, seqCount) else action,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        pressed -> palette.onPrimary.copy(alpha = 0.72f)
                        seqCount > 0 -> palette.primary.copy(alpha = 0.9f)
                        mapped -> palette.secondary.copy(alpha = 0.85f)
                        else -> palette.muted
                    },
                    fontSize = 8.5.sp,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "›",
                color = if (pressed) palette.onPrimary else palette.muted.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

private fun keycapShortLabel(name: String): String = when (name) {
    "UP" -> "▲"
    "DOWN" -> "▼"
    "LEFT" -> "◀"
    "RIGHT" -> "▶"
    "BACKSPACE" -> "BKSP"
    "PRINTSCREEN" -> "PRTSC"
    "SCROLL_LOCK" -> "SCRLK"
    "CAPS_LOCK" -> "CAPS"
    "PAGE_UP" -> "PG UP"
    "PAGE_DOWN" -> "PG DN"
    "SEMICOLON" -> ";"
    "APOSTROPHE" -> "'"
    "GRAVE" -> "`"
    "BACKSLASH" -> "\\"
    "BACKSLASH_BS" -> "ISO \\"
    "BRACE_L" -> "["
    "BRACE_R" -> "]"
    "MINUS" -> "-"
    "EQUAL" -> "="
    "COMMA" -> ","
    "PERIOD" -> "."
    "SLASH" -> "/"
    "KP_DIVIDE" -> "KP /"
    "KP_MULTIPLY" -> "KP *"
    "KP_MINUS" -> "KP -"
    "KP_PLUS" -> "KP +"
    "KP_ENTER" -> "KP ENT"
    "KP_DOT" -> "KP ."
    else -> name
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
