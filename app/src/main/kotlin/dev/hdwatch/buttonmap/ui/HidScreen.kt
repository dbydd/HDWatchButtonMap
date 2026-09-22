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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
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

    ScreenScaffold(title = stringResource(R.string.set_hid_title)) {
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
                    MicroLabel(stringResource(R.string.set_hid_link_status), palette.primary.copy(alpha = 0.6f))
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
                        text = stringResource(R.string.set_hid_host, host),
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
                    MicroLabel(stringResource(R.string.set_label_keep_alive), palette.primary.copy(alpha = 0.6f))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (settings.keepAliveService) {
                            stringResource(R.string.set_hid_keep_alive_running)
                        } else {
                            stringResource(R.string.set_hid_keep_alive_off)
                        },
                        color = if (settings.keepAliveService) palette.primary else palette.danger,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Text(
                    text = when {
                        bluetooth == null -> stringResource(R.string.set_hid_no_bluetooth)
                        hasPermission -> stringResource(R.string.set_hid_permission_granted)
                        else -> stringResource(R.string.set_hid_permission_missing)
                    },
                    color = if (bluetooth != null && !hasPermission) palette.danger else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(R.string.set_hid_disconnect_hint),
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
                text = stringResource(R.string.set_hid_discoverable),
                onClick = { actions.requestDiscoverable() },
            )
            ActionButton(
                text = stringResource(R.string.set_hid_permission),
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
                text = stringResource(R.string.set_hid_reconnect),
                onClick = { bluetooth?.tryReconnect() },
                tone = HdTone.Accent,
                enabled = bluetooth != null,
            )
            ActionButton(
                text = stringResource(R.string.set_hid_disconnect),
                onClick = { bluetooth?.disconnectTarget() },
                enabled = bluetooth != null,
            )
            ActionButton(
                text = stringResource(R.string.set_hid_reregister),
                onClick = { bluetooth?.reRegister() },
                enabled = bluetooth != null,
            )
        }

        SectionLabel(stringResource(R.string.set_hid_hosts))
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(R.string.set_hid_no_hosts),
                color = palette.muted,
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
        hosts.forEach { device ->
            HostRow(
                label = bluetooth?.hostLabel(device) ?: device.address,
                hint = stringResource(R.string.set_hid_tap_reconnect),
            ) { bluetooth?.tryReconnect() }
        }

        SectionLabel(stringResource(R.string.set_hid_transport_mode))
        SwitchRow(
            label = stringResource(R.string.set_hid_log_transport),
            checked = settings.forceLoggingTransport,
            onCheckedChange = { on ->
                app.configRepo.updateSettings { it.copy(forceLoggingTransport = on) }
            },
            hint = if (settings.forceLoggingTransport) {
                stringResource(R.string.set_hid_log_transport_on)
            } else {
                stringResource(R.string.set_hid_log_transport_off)
            },
        )
        Text(
            text = stringResource(R.string.set_hid_log_transport_note),
            color = palette.muted,
            fontSize = 9.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionLabel(stringResource(R.string.set_hid_external_config))
        Text(
            text = stringResource(
                R.string.set_hid_adb_push,
                externalPath ?: stringResource(R.string.set_hid_no_external_dir),
            ),
            fontFamily = FontFamily.Monospace,
            color = palette.primary,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.set_hid_external_note),
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
