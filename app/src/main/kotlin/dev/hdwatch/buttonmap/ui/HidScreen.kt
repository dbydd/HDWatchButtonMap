package dev.hdwatch.buttonmap.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.hid.TransportKind

/**
 * 蓝牙 HID 屏：链路状态卡（传输 / 主机 / 保活 / 权限）、已配对主机列表、
 * 可发现 / 权限 / 重连 / 断开 / 重注册操作、日志模拟传输开关与外部配置路径提示。
 */

@Composable
fun HidScreen() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val actions = LocalPlatformActions.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    // forSettings also refreshes transports.active, so toggling logging mode below
    // re-points the app at the chosen transport instead of leaving the old one live.
    val transport = remember(settings) { app.transports.forSettings(settings) }
    val status by transport.status.collectAsState()

    val bluetooth = app.transports.bluetoothOrNull()
    val hasPermission = bluetooth?.hasConnectPermission() == true
    val hosts: List<BluetoothDevice> =
        if (bluetooth != null && hasPermission) bluetooth.bondedHosts() else emptyList()
    val externalPath = app.configRepo.externalFile?.absolutePath

    ScreenScaffold(title = "蓝牙 HID") {
        HdPanel(
            modifier = Modifier
                .widthIn(min = 140.dp, max = 178.dp)
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicroLabel("链路状态", palette.primary.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (status.kind == TransportKind.BLUETOOTH) "BLUETOOTH" else "LOG ONLY",
                        fontFamily = FontFamily.Monospace,
                        color = palette.secondary.copy(alpha = 0.8f),
                        fontSize = 8.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = status.label,
                    color = palette.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                status.hostName?.let { host ->
                    Text(
                        text = "HOST $host",
                        fontFamily = FontFamily.Monospace,
                        color = palette.primary,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = status.detail,
                    color = palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Hairline()
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicroLabel("保活", palette.primary.copy(alpha = 0.6f))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (settings.keepAliveService) "前台服务运行中" else "未开启",
                        color = if (settings.keepAliveService) palette.primary else palette.danger,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Text(
                    text = when {
                        bluetooth == null -> "本机无蓝牙 HID 通路"
                        hasPermission -> "已授予蓝牙连接权限"
                        else -> "缺少蓝牙连接权限，先授权再操作"
                    },
                    color = if (bluetooth != null && !hasPermission) palette.danger else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "断连多为后台注销所致，保持保活开关开启",
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(
                text = "请求可发现",
                onClick = { actions.requestDiscoverable() },
            )
            ActionButton(
                text = "蓝牙权限",
                onClick = { actions.requestBluetoothPermissions() },
                tone = if (hasPermission) HdTone.Neutral else HdTone.Accent,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(
                text = "重连",
                onClick = { bluetooth?.tryReconnect() },
                tone = HdTone.Accent,
                enabled = bluetooth != null,
            )
            ActionButton(
                text = "断开",
                onClick = { bluetooth?.disconnectTarget() },
                enabled = bluetooth != null,
            )
            ActionButton(
                text = "重新注册",
                onClick = { bluetooth?.reRegister() },
                enabled = bluetooth != null,
            )
        }

        SectionLabel("已配对主机")
        if (hosts.isEmpty()) {
            Text(
                text = "没有可用主机：先在系统设置配对电脑，或授予蓝牙权限",
                color = palette.muted,
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
        hosts.forEach { device ->
            HostRow(
                label = bluetooth?.hostLabel(device) ?: device.address,
                hint = "点击重连",
            ) { bluetooth?.tryReconnect() }
        }

        SectionLabel("传输模式")
        SwitchRow(
            label = "日志模拟传输",
            checked = settings.forceLoggingTransport,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(forceLoggingTransport = on) }
            },
            hint = if (settings.forceLoggingTransport) "报文只写日志，不发蓝牙" else "通过蓝牙 HID 发送到主机",
        )
        Text(
            text = "模拟器没有蓝牙协议栈，必须留在日志模拟；真机关闭后即可连接电脑主机。",
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionLabel("外部配置文件")
        Text(
            text = "adb push hdmap.json " + (externalPath ?: "（无外部目录）"),
            fontFamily = FontFamily.Monospace,
            color = palette.primary,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "把配置推到手表外部文件目录，启动时自动导入；也可在设置里手动重载。",
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
        )
    }
}

/** Paired-host row: monospace address, tap to reconnect. */
@Composable
private fun HostRow(
    label: String,
    hint: String,
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                color = if (pressed) palette.onPrimary else palette.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.7f) else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                )
            }
        }
    }
}
