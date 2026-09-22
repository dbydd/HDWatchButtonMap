package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.config.Macro
import kotlinx.coroutines.delay

/**
 * 宏命令屏：宏卡片（试跑 / 启停 / 二次点击确认删除），顶部一行配置仓库状态。
 * 同时存放其余配置屏共用的小原语（ActionButton / SwitchRow / SectionLabel）。
 */

private const val DELETE_CONFIRM_MS = 4000L

/** configRepo.status 中表示读取/解析/写入失败的关键字；命中时用 danger 色呈现。 */
internal fun isStatusError(status: String): Boolean =
    status.contains("失败") || status.contains("拒绝") || status.contains("错误") || status.contains("缺失")

/** 配置屏动作按钮的配色档位：中性（描边感）、主强调、危险。 */
enum class HdTone { Neutral, Accent, Danger }

/** 配置屏通用按钮：≥40dp 触控高度，居中文案，禁用态降饱和。 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HdTone = HdTone.Neutral,
    enabled: Boolean = true,
) {
    val palette = LocalHdPalette.current
    val accent = when (tone) {
        HdTone.Neutral -> palette.secondary
        HdTone.Accent -> palette.primary
        HdTone.Danger -> palette.danger
    }
    Box(
        modifier = modifier
            .heightIn(min = 34.dp)
            .background(
                accent.copy(alpha = if (enabled) 0.13f else 0.05f),
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                accent.copy(alpha = if (enabled) 0.5f else 0.18f),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) accent else palette.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 配置屏开关行：左侧标题+说明，右侧自绘胶囊开关（整行可点）。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
) {
    val palette = LocalHdPalette.current
    Row(
        modifier = modifier
            .padding(vertical = 2.dp)
            .background(palette.surface, RoundedCornerShape(9.dp))
            .border(1.dp, palette.secondary.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotEmpty()) {
                Text(hint, color = palette.muted, fontSize = 9.sp, maxLines = 2)
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(width = 38.dp, height = 20.dp)
                .background(
                    if (checked) palette.primary else palette.muted.copy(alpha = 0.30f),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(16.dp)
                    .background(if (checked) palette.onPrimary else palette.secondary, CircleShape),
            )
        }
    }
}

/** 分区小标题，与卡片之间留一点呼吸空间。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalHdPalette.current
    Text(
        text = text,
        color = palette.secondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
fun MacroListScreen() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val config by app.configRepo.config.collectAsState()
    val status by app.configRepo.status.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingDelete) {
        if (pendingDelete != null) {
            delay(DELETE_CONFIRM_MS)
            pendingDelete = null
        }
    }

    ScreenScaffold(title = "宏命令") {
        Text(
            text = status,
            color = if (isStatusError(status)) palette.danger else palette.muted,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        )
        if (config.macros.isEmpty()) {
            Text("没有宏；用 hdmap.json 或导入添加", color = palette.muted, fontSize = 12.sp)
        }
        config.macros.forEach { macro ->
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
    Column(
        modifier = Modifier
            .widthIn(min = 168.dp)
            .widthIn(max = 210.dp)
            .padding(vertical = 3.dp)
            .background(palette.surface, RoundedCornerShape(9.dp))
            .border(1.dp, palette.primary.copy(alpha = if (macro.enabled) 0.35f else 0.15f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = macro.name,
                color = palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = if (macro.enabled) "启用中" else "已停用",
                color = if (macro.enabled) palette.primary else palette.muted,
                fontSize = 10.sp,
            )
        }
        Text(
            text = macro.sequenceDisplay + " · " + macro.steps.size + " 步" +
                if (macro.repeat > 1) " · x" + macro.repeat else "",
            color = palette.secondary.copy(alpha = 0.75f),
            fontSize = 11.sp,
        )
        Row(
            modifier = Modifier.padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton("试跑", onRun, tone = HdTone.Accent)
            ActionButton(if (macro.enabled) "停用" else "启用", onToggle)
            ActionButton(
                text = if (confirming) "确认删除" else "删除",
                onClick = onDelete,
                tone = HdTone.Danger,
            )
        }
    }
}
