package dev.hdwatch.buttonmap.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.hid.TransportKind

/**
 * 蓝牙 HID 屏：当前传输状态卡、已配对主机列表、可发现/权限/重连/断开/重注册操作、
 * 日志模拟传输开关，以及 adb push 外部配置文件的路径提示。
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
        Column(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .background(palette.surface, RoundedCornerShape(9.dp))
                .border(1.dp, palette.secondary.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = status.label,
                    color = palette.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = if (status.kind == TransportKind.BLUETOOTH) "蓝牙" else "日志",
                    color = palette.secondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
            status.hostName?.let { host ->
                Text("主机 $host", color = palette.primary, fontSize = 11.sp, maxLines = 1)
            }
            Text(status.detail, color = palette.muted, fontSize = 11.sp, maxLines = 2)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("保活 ", color = palette.muted, fontSize = 9.sp, maxLines = 1)
                Text(
                    text = if (settings.keepAliveService) "前台服务运行中" else "未开启",
                    color = if (settings.keepAliveService) palette.primary else palette.danger,
                    fontSize = 11.sp,
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
                color = when {
                    bluetooth == null -> palette.muted
                    hasPermission -> palette.muted
                    else -> palette.danger
                },
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
        Text(
            text = "断连多为后台注销所致，保持保活开关开启",
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )

        Row(
            modifier = Modifier.padding(top = 2.dp),
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
                fontSize = 11.sp,
                maxLines = 2,
            )
        }
        hosts.forEach { device ->
            MenuRow(
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
        )

        SectionLabel("外部配置文件")
        Text(
            text = "adb push hdmap.json " + (externalPath ?: "（无外部目录）"),
            color = palette.primary,
            fontSize = 9.sp,
            maxLines = 1,
        )
        Text(
            text = "把配置推到手表外部文件目录，启动时自动导入；也可在设置里手动重载。",
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
        )
    }
}
