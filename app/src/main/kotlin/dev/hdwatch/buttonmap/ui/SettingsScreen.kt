package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp

/**
 * 设置屏：表冠阈值 / 序列超时 / 打字延迟步进器，反转与未知输入捕获开关，
 * 手势传感器→符号绑定（点击循环 G1..G4→解绑），以及配置的重载 / 导入 / 导出 / 写外部文件。
 */

private val GESTURE_SLOTS = listOf("G1", "G2", "G3", "G4")

@Composable
fun SettingsScreen() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val actions = LocalPlatformActions.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    val status by app.configRepo.status.collectAsState()
    // Sensors cannot appear or disappear while this screen is up.
    val candidates = remember { app.sensorHub.candidates() }

    ScreenScaffold(title = "设置") {
        SectionLabel("表冠旋转")
        StepperRow(
            label = "旋转阈值",
            value = settings.rotateThreshold.toLong(),
            suffix = " 格",
            min = 10,
            max = 90,
            step = 10,
            onChange = { v -> app.configRepo.updateSettings { it.copy(rotateThreshold = v.toInt()) } },
        )
        SwitchRow(
            label = "反转正负",
            checked = settings.invertRotation,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(invertRotation = on) } },
            hint = if (settings.invertRotation) "顺/逆时针对调" else "表冠方向按系统默认",
        )

        SectionLabel("序列")
        StepperRow(
            label = "序列超时",
            value = settings.sequenceTimeoutMs,
            suffix = " ms",
            min = 1000,
            max = 8000,
            step = 500,
            onChange = { v -> app.configRepo.updateSettings { it.copy(sequenceTimeoutMs = v) } },
        )
        SwitchRow(
            label = "捕获未知输入",
            checked = settings.captureUnknownInput,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(captureUnknownInput = on) }
            },
            hint = "在输入日志里显示原始按键与事件",
        )

        SectionLabel("打字")
        StepperRow(
            label = "字符间隔",
            value = settings.textDelayMs,
            suffix = " ms",
            min = 10,
            max = 200,
            step = 10,
            onChange = { v -> app.configRepo.updateSettings { it.copy(textDelayMs = v) } },
        )

        SectionLabel("手势传感器")
        SwitchRow(
            label = "启用手势传感器",
            checked = settings.gestureSensorsEnabled,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(gestureSensorsEnabled = on) }
            },
            hint = "打开才注册监听；模拟器 HAL 会崩，实机也保持手动开启",
        )
        if (candidates.isEmpty()) {
            Text("本机无可用手势传感器", color = palette.muted, fontSize = 11.sp)
        }
        candidates.forEachIndexed { index, candidate ->
            val name = candidate.sensor.name
            val effective = app.sensorHub.symbolFor(index, candidate.sensor)
            val binding = when (val bound = settings.sensorToSymbol[name]) {
                null -> "默认 ${effective?.display ?: "无"}"
                "" -> "未绑定"
                else -> bound
            }
            MenuRow(
                label = name,
                hint = "类型 #${candidate.type} · 触发 $binding · 点击切换 G1→G4→解绑",
            ) {
                val order = GESTURE_SLOTS + ""
                val next = order[(order.indexOf(settings.sensorToSymbol[name]) + 1) % order.size]
                app.configRepo.updateSettings {
                    it.copy(sensorToSymbol = it.sensorToSymbol + (name to next))
                }
            }
        }

        SectionLabel("配置文件")
        Row(
            modifier = Modifier
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton("重载", { app.configRepo.reload() })
            ActionButton("导入 JSON", { actions.importFile() })
        }
        Row(
            modifier = Modifier
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton("导出 JSON", { actions.exportFile() })
            ActionButton("写外部文件", { app.configRepo.copyToExternal() })
        }
        Text(
            text = status,
            color = if (isStatusError(status)) palette.danger else palette.muted,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** 数值 ± 步进行：标题 + 减/值/加，值域钳制在 [min, max]。 */
@Composable
private fun StepperRow(
    label: String,
    value: Long,
    suffix: String,
    min: Long,
    max: Long,
    step: Long,
    onChange: (Long) -> Unit,
) {
    val palette = LocalHdPalette.current
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(palette.surface, RoundedCornerShape(9.dp))
            .border(1.dp, palette.secondary.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        ActionButton(
            text = "-",
            onClick = { onChange((value - step).coerceIn(min, max)) },
            enabled = value > min,
        )
        Text(
            text = "$value$suffix",
            color = palette.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        ActionButton(
            text = "+",
            onClick = { onChange((value + step).coerceIn(min, max)) },
            enabled = value < max,
        )
    }
}
