package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.input.CapStatus
import dev.hdwatch.buttonmap.input.GestureBackend
import dev.hdwatch.buttonmap.input.Symbol

/**
 * 设置屏：表冠全局阈值 / 本模式阈值 / 序列超时 / 打字延迟步进器，反转与未知输入捕获开关，
 * 保活开关，手势能力卡（探测式兼容层：状态点 + 绑定循环），以及配置的重载 / 导入 / 导出 / 写外部文件。
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
    val gestures = app.gestures as GestureBackend
    var caps by remember { mutableStateOf(gestures.capabilities()) }
    val boundSlots = remember { mutableStateMapOf<String, Symbol?>() }
    LaunchedEffect(settings.gestureSensorsEnabled) {
        gestures.refresh()
        caps = gestures.capabilities()
    }

    // Leaving this screen is the natural moment to re-read everything the
    // five-tap easter egg may have changed: the new profile and its gesture
    // wiring should be live by the time the pad comes back.
    DisposableEffect(Unit) {
        onDispose {
            app.configRepo.reload()
            app.gestures.refresh()
            app.gestures.stop()
            app.gestures.start()
        }
    }

    ScreenScaffold(title = "设置") {
        SectionLabel("表冠旋转")
        StepperRow(
            label = "旋转阈值",
            value = settings.rotateThreshold.toLong(),
            suffix = " 格",
            min = 1,
            max = 15,
            step = 1,
            onChange = { v -> app.configRepo.updateSettings { it.copy(rotateThreshold = v.toInt()) } },
        )
        StepperRow(
            label = "本模式阈值",
            value = config.effectiveRotateThreshold().toLong(),
            suffix = " 格",
            min = 1,
            max = 15,
            step = 1,
            onChange = { v ->
                app.configRepo.updateProfile(config.activeProfileId) {
                    it.copy(rotateThreshold = v.toInt())
                }
            },
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(
                text = "跟随全局",
                tone = HdTone.Accent,
                onClick = {
                    app.configRepo.updateProfile(config.activeProfileId) {
                        it.copy(rotateThreshold = null)
                    }
                },
            )
        }
        Text(
            text = if (config.activeProfile.rotateThreshold == null) {
                "本模式当前跟随全局（全局 ${settings.rotateThreshold} 格）"
            } else {
                "本模式覆盖全局（全局 ${settings.rotateThreshold} 格）"
            },
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp),
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
        if (caps.isEmpty()) {
            Text(
                "本机无可用手势能力",
                color = palette.muted,
                fontSize = 9.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        caps.forEach { cap ->
            val bound = boundSlots[cap.id]
                ?: settings.sensorToSymbol[cap.id]?.let { Symbol.fromCode(it) }
            GestureCapabilityRow(
                label = cap.label,
                detail = cap.detail,
                status = cap.status,
                bound = bound,
                onClick = {
                    if (cap.status == CapStatus.AVAILABLE) {
                        val order = GESTURE_SLOTS.map { Symbol.fromCode(it) } + listOf(null)
                        val next = order[(order.indexOf(bound) + 1) % order.size]
                        gestures.bind(cap.id, next)
                        boundSlots[cap.id] = next
                    }
                },
            )
        }

        SectionLabel("保活")
        SwitchRow(
            label = "前台服务保活",
            checked = settings.keepAliveService,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(keepAliveService = on) }
            },
            hint = "防退后台断连 + 通知栏常驻",
        )

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
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        // ---- easter egg: five taps on the build label unlock the HD2 pack ----
        val version = remember {
            runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        var taps by remember { mutableStateOf(0) }
        LaunchedEffect(taps) {
            if (taps in 1..4) {
                delay(1600)
                taps = 0
            }
        }
        Text(
            text = "HD MAP $version",
            color = palette.muted.copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 10.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        taps += 1
                        if (taps >= 5) {
                            taps = 0
                            if (app.configRepo.installBundledPack("hd2_pack.json")) {
                                app.haptics.macro()
                            } else {
                                app.haptics.error()
                            }
                        }
                        waitForUpOrCancellation()
                    }
                },
        )
    }
}

/**
 * 手势能力卡一行：状态色点 + 标签 + 详情 + 当前绑定符号；只有 AVAILABLE 可点，
 * 点击循环绑定 G1→G2→G3→G4→解绑（写入走 GestureBackend.bind）。
 */
@Composable
private fun GestureCapabilityRow(
    label: String,
    detail: String,
    status: CapStatus,
    bound: Symbol?,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val bindable = status == CapStatus.AVAILABLE
    val capColor = when (status) {
        CapStatus.AVAILABLE -> palette.primary
        CapStatus.GATED -> palette.muted
        CapStatus.NEEDS_PERMISSION -> palette.danger
        CapStatus.BROKEN -> palette.muted
    }
    HdPanel(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 168.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        onClick = if (bindable) onClick else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(capColor, RoundedCornerShape(1.5.dp))
                    .border(1.dp, palette.hairline, RoundedCornerShape(1.5.dp)),
            )
            Column(modifier = Modifier.padding(start = 7.dp)) {
                Text(
                    text = label,
                    color = if (status == CapStatus.BROKEN) palette.muted else palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textDecoration = if (status == CapStatus.BROKEN) TextDecoration.LineThrough else null,
                )
                Text(
                    text = buildString {
                        append(
                            when (status) {
                                CapStatus.AVAILABLE -> "可用"
                                CapStatus.GATED -> "未启用监听"
                                CapStatus.NEEDS_PERMISSION -> "缺权限"
                                CapStatus.BROKEN -> "不可用"
                            },
                        )
                        append(" · 绑定 ")
                        append(bound?.display ?: "无")
                    },
                    color = palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                )
            }
        }
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
    HdPanel(
        modifier = Modifier
            .widthIn(min = 132.dp, max = 168.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                color = palette.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ActionButton(
                    text = "-",
                    onClick = { onChange((value - step).coerceIn(min, max)) },
                    enabled = value > min,
                )
                Text(
                    text = "$value$suffix",
                    color = palette.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                ActionButton(
                    text = "+",
                    onClick = { onChange((value + step).coerceIn(min, max)) },
                    enabled = value < max,
                )
            }
        }
    }
}
