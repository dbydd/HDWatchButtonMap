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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
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
    val statusIsError by app.configRepo.statusIsError.collectAsState()
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

    ScreenScaffold(title = stringResource(R.string.set_title)) {
        SectionLabel(stringResource(R.string.set_section_rotation))
        StepperRow(
            label = stringResource(R.string.set_step_rotate_threshold),
            value = settings.rotateThreshold.toLong(),
            pluralRes = R.plurals.set_step_value_clicks,
            min = 1,
            max = 15,
            step = 1,
            onChange = { v -> app.configRepo.updateSettings { it.copy(rotateThreshold = v.toInt()) } },
        )
        StepperRow(
            label = stringResource(R.string.set_step_profile_threshold),
            value = config.effectiveRotateThreshold().toLong(),
            pluralRes = R.plurals.set_step_value_clicks,
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
                text = stringResource(R.string.set_action_follow_global),
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
                stringResource(R.string.set_rotate_follow_global, settings.rotateThreshold)
            } else {
                stringResource(R.string.set_rotate_override_global, settings.rotateThreshold)
            },
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )
        SwitchRow(
            label = stringResource(R.string.set_switch_invert),
            checked = settings.invertRotation,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(invertRotation = on) } },
            hint = if (settings.invertRotation) {
                stringResource(R.string.set_switch_invert_on)
            } else {
                stringResource(R.string.set_switch_invert_off)
            },
        )

        SectionLabel(stringResource(R.string.set_section_sequence))
        StepperRow(
            label = stringResource(R.string.set_step_sequence_timeout),
            value = settings.sequenceTimeoutMs,
            valueRes = R.string.set_step_value_ms,
            min = 1000,
            max = 8000,
            step = 500,
            onChange = { v -> app.configRepo.updateSettings { it.copy(sequenceTimeoutMs = v) } },
        )
        SwitchRow(
            label = stringResource(R.string.set_switch_capture_unknown),
            checked = settings.captureUnknownInput,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(captureUnknownInput = on) }
            },
            hint = stringResource(R.string.set_switch_capture_unknown_hint),
        )

        SectionLabel(stringResource(R.string.set_section_typing))
        StepperRow(
            label = stringResource(R.string.set_step_text_delay),
            value = settings.textDelayMs,
            valueRes = R.string.set_step_value_ms,
            min = 10,
            max = 200,
            step = 10,
            onChange = { v -> app.configRepo.updateSettings { it.copy(textDelayMs = v) } },
        )

        SectionLabel(stringResource(R.string.set_section_gestures))
        SwitchRow(
            label = stringResource(R.string.set_switch_gestures),
            checked = settings.gestureSensorsEnabled,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(gestureSensorsEnabled = on) }
            },
            hint = stringResource(R.string.set_switch_gestures_hint),
        )
        if (caps.isEmpty()) {
            Text(
                stringResource(R.string.set_gestures_none),
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

        SectionLabel(stringResource(R.string.set_section_feedback))
        SwitchRow(
            label = stringResource(R.string.set_switch_vibration),
            checked = settings.vibrationEnabled,
            onCheckedChange = { on -> app.configRepo.updateSettings { it.copy(vibrationEnabled = on) } },
            hint = stringResource(R.string.set_switch_vibration_hint),
        )

        SectionLabel(stringResource(R.string.set_label_keep_alive))
        SwitchRow(
            label = stringResource(R.string.set_switch_keep_alive),
            checked = settings.keepAliveService,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(keepAliveService = on) }
            },
            hint = stringResource(R.string.set_switch_keep_alive_hint),
        )

        SectionLabel(stringResource(R.string.set_section_config))
        Row(
            modifier = Modifier
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(stringResource(R.string.set_action_reload), { app.configRepo.reload() })
            ActionButton(stringResource(R.string.set_action_import), { actions.importFile() })
        }
        Row(
            modifier = Modifier
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(stringResource(R.string.set_action_export), { actions.exportFile() })
            ActionButton(stringResource(R.string.set_action_write_external), { app.configRepo.copyToExternal() })
        }
        Text(
            text = status,
            color = if (statusIsError) palette.danger else palette.muted,
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
            text = stringResource(R.string.set_version_line, version),
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
    val statusText = when (status) {
        CapStatus.AVAILABLE -> stringResource(R.string.set_gesture_available)
        CapStatus.GATED -> stringResource(R.string.set_gesture_gated)
        CapStatus.NEEDS_PERMISSION -> stringResource(R.string.set_gesture_needs_permission)
        CapStatus.BROKEN -> stringResource(R.string.set_gesture_broken)
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
                    text = stringResource(
                        R.string.set_gesture_row,
                        statusText,
                        bound?.display ?: stringResource(R.string.set_gesture_unbound),
                    ),
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
    valueRes: Int? = null,
    pluralRes: Int? = null,
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
                    text = when {
                        pluralRes != null -> pluralStringResource(pluralRes, value.toInt(), value)
                        valueRes != null -> stringResource(valueRes, value)
                        else -> value.toString()
                    },
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
