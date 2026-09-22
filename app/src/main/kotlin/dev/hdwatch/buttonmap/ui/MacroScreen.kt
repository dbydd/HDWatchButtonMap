package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.Macro
import kotlinx.coroutines.delay

/**
 * 宏命令屏：宏卡片（试跑 / 启停 / 二次点击确认删除），顶部一行配置仓库状态。
 * 同时存放其余配置屏共用的小原语（ActionButton / SwitchRow / SectionLabel）。
 */

private const val DELETE_CONFIRM_MS = 4000L

/** 配置屏动作按钮的配色档位：中性（描边感）、主强调、危险。 */
enum class HdTone { Neutral, Accent, Danger }

/** 配置屏通用按钮：玻璃面板，按下泛金，禁用态降饱和。 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HdTone = HdTone.Neutral,
    enabled: Boolean = true,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = when {
        !enabled -> palette.muted
        pressed -> palette.onPrimary
        tone == HdTone.Accent -> palette.primary
        tone == HdTone.Danger -> palette.danger
        else -> palette.secondary
    }
    HdPanel(
        modifier = modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(7.dp),
        pressed = enabled && pressed,
        interactionSource = interaction,
        onClick = if (enabled) onClick else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 配置屏开关行：玻璃面板，右侧矩形推钮开关（整行可点）。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = modifier
            .widthIn(min = 132.dp, max = 168.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = if (pressed) palette.onPrimary else palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        color = if (pressed) palette.onPrimary.copy(alpha = 0.7f) else palette.muted,
                        fontSize = 9.sp,
                        maxLines = 2,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(width = 36.dp, height = 18.dp)
                    .background(
                        if (checked) {
                            Brush.horizontalGradient(listOf(palette.goldDeep, palette.primary))
                        } else {
                            Brush.verticalGradient(listOf(palette.panelBottom, Color.Black.copy(alpha = 0.35f)))
                        },
                        RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, palette.hairline, RoundedCornerShape(4.dp)),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = 14.dp, height = 14.dp)
                        .background(
                            if (checked) palette.onPrimary else palette.secondary,
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
    }
}

/** 分区标题：终端微标签 + 渐隐细线。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalHdPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MicroLabel(text, palette.primary.copy(alpha = 0.65f))
        Spacer(Modifier.height(3.dp))
        Hairline()
    }
}

@Composable
fun MacroListScreen() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val config by app.configRepo.config.collectAsState()
    val status by app.configRepo.status.collectAsState()
    val statusIsError by app.configRepo.statusIsError.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingDelete) {
        if (pendingDelete != null) {
            delay(DELETE_CONFIRM_MS)
            pendingDelete = null
        }
    }

    ScreenScaffold(title = stringResource(R.string.scr_macro_title)) {
        if (config.activeProfile.kind != ProfileKind.MACRO) {
            Text(
                text = stringResource(R.string.scr_macro_not_macro_mode),
                color = palette.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        }
        Text(
            text = status,
            color = if (statusIsError) palette.danger else palette.muted,
            fontSize = 11.sp,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        )
        if (config.activeProfile.macros.isEmpty()) {
            Text(stringResource(R.string.scr_macro_empty), color = palette.muted, fontSize = 11.sp, maxLines = 2)
        }
        config.activeProfile.macros.forEach { macro ->
            MacroCard(
                macro = macro,
                confirming = pendingDelete == macro.id,
                onRun = { app.runner.runMacro(macro) },
                onToggle = { app.configRepo.setMacroEnabled(macro.id, !macro.enabled) },
                onDelete = {
                    if (pendingDelete == macro.id) {
                        app.configRepo.deleteMacro(macro.id)
                        pendingDelete = null
                    } else {
                        pendingDelete = macro.id
                    }
                },
            )
        }
    }
}

@Composable
private fun MacroCard(
    macro: Macro,
    confirming: Boolean,
    onRun: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalHdPalette.current
    HdPanel(
        modifier = Modifier
            .widthIn(min = 150.dp, max = 178.dp)
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = macro.name,
                    color = palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box(
                    Modifier
                        .size(5.dp)
                        .background(
                            if (macro.enabled) palette.primary else palette.muted,
                            RoundedCornerShape(1.dp),
                        ),
                )
                Text(
                    text = " " + stringResource(
                        if (macro.enabled) R.string.scr_macro_state_on else R.string.scr_macro_state_off,
                    ),
                    color = if (macro.enabled) palette.primary.copy(alpha = 0.85f) else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = macro.sequenceDisplay + " · " + stringResource(R.string.scr_macro_steps, macro.steps.size) +
                    if (macro.repeat > 1) " · x" + macro.repeat else "",
                fontFamily = FontFamily.Monospace,
                color = palette.secondary.copy(alpha = 0.8f),
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
                maxLines = 2,
            )
            Row(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionButton(stringResource(R.string.scr_macro_run), onRun, tone = HdTone.Accent)
                ActionButton(
                    stringResource(if (macro.enabled) R.string.scr_macro_disable else R.string.scr_macro_enable),
                    onToggle,
                )
                ActionButton(
                    text = stringResource(
                        if (confirming) R.string.scr_macro_confirm_delete else R.string.scr_macro_delete,
                    ),
                    onClick = onDelete,
                    tone = HdTone.Danger,
                )
            }
        }
    }
}
