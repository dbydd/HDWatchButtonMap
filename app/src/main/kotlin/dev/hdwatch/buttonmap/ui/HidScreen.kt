package dev.hdwatch.buttonmap.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.hid.TransportKind
import dev.hdwatch.buttonmap.hid.TransportState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bluetooth HID screen: live link status card, aligned action grid (including
 * Clear Host Lock to break cached BT links), sorted paired-host switcher with
 * live connected/switching indicators, transport mode toggle, and adb path.
 */
@Composable
fun HidScreen() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val actions = LocalPlatformActions.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val settings = config.settings
    val transport = remember(settings.forceLoggingTransport) {
        app.transports.forSettings(settings)
    }
    val status by transport.status.collectAsState()

    val bluetooth = app.transports.bluetoothOrNull()
    val hasPermission = bluetooth?.hasConnectPermission() == true
    val nullFlow = remember { MutableStateFlow<String?>(null) }
    val activeAddr by (bluetooth?.activeHostAddress ?: nullFlow).collectAsState()
    val connectedAddr by (bluetooth?.connectedHostAddress ?: nullFlow).collectAsState()
    val switchingAddr by (bluetooth?.switchingHostAddress ?: nullFlow).collectAsState()
    var refreshNonce by remember { mutableStateOf(0) }

    val hosts: List<BluetoothDevice> = remember(bluetooth, hasPermission, refreshNonce, connectedAddr, activeAddr) {
        if (bluetooth == null || !hasPermission) {
            emptyList()
        } else {
            bluetooth.bondedHosts().sortedWith(
                compareBy<BluetoothDevice>(
                    { addr -> if (runCatching { addr.address }.getOrNull() == connectedAddr) 0 else 1 },
                    { addr -> if (runCatching { addr.address }.getOrNull() == activeAddr) 0 else 1 },
                    { dev -> bluetooth.hostLabel(dev).lowercase() },
                ),
            )
        }
    }
    val externalPath = app.configRepo.externalFile?.absolutePath

    ScreenScaffold(title = stringResource(R.string.set_hid_title)) {
        // ---- Link status card ----
        HdPanel(
            modifier = Modifier
                .settingsCardWidth()
                .padding(vertical = 2.5.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                when (status.state) {
                                    TransportState.CONNECTED -> palette.primary
                                    TransportState.CONNECTING -> palette.secondary
                                    TransportState.NEEDS_PERMISSION,
                                    TransportState.REGISTER_FAILED,
                                    TransportState.UNAVAILABLE,
                                    TransportState.NO_ADAPTER,
                                    -> palette.danger
                                    else -> palette.muted
                                },
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(5.dp))
                    MicroLabel(stringResource(R.string.set_hid_link_status), palette.primary.copy(alpha = 0.65f))
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (status.kind == TransportKind.BLUETOOTH) "BT HID" else "SIM",
                        fontFamily = FontFamily.Monospace,
                        color = palette.secondary.copy(alpha = 0.8f),
                        fontSize = 8.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
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
                    fontSize = 8.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Hairline()
                Spacer(Modifier.height(5.dp))
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
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                if (bluetooth == null || !hasPermission) {
                    Text(
                        text = if (bluetooth == null) {
                            stringResource(R.string.set_hid_no_bluetooth)
                        } else {
                            stringResource(R.string.set_hid_permission_missing)
                        },
                        color = palette.danger,
                        fontSize = 8.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ---- Aligned 2-column action controls ----
        ActionGridRow(
            leftText = stringResource(R.string.set_hid_reconnect),
            onLeft = {
                haptics.tick()
                bluetooth?.tryReconnect()
            },
            rightText = stringResource(R.string.set_hid_disconnect),
            onRight = {
                haptics.tick()
                bluetooth?.disconnectTarget()
            },
            leftTone = HdTone.Accent,
            leftEnabled = bluetooth != null,
            rightEnabled = bluetooth != null,
        )
        ActionGridRow(
            leftText = stringResource(R.string.set_hid_clear_lock),
            onLeft = {
                haptics.tick()
                bluetooth?.clearHostLock()
                refreshNonce++
            },
            rightText = stringResource(R.string.set_hid_reregister),
            onRight = {
                haptics.tick()
                bluetooth?.reRegister()
            },
            leftTone = HdTone.Danger,
            leftEnabled = bluetooth != null,
            rightEnabled = bluetooth != null,
        )
        ActionGridRow(
            leftText = stringResource(R.string.set_hid_discoverable),
            onLeft = { actions.requestDiscoverable() },
            rightText = stringResource(R.string.set_hid_permission),
            onRight = {
                actions.requestBluetoothPermissions()
                bluetooth?.ensureProxy()
                refreshNonce++
            },
            rightTone = if (hasPermission) HdTone.Neutral else HdTone.Accent,
        )

        // ---- Paired host list (switchable without cache lock) ----
        SectionLabel(stringResource(R.string.set_hid_hosts))
        if (hosts.isEmpty()) {
            Text(
                text = stringResource(R.string.set_hid_no_hosts),
                color = palette.muted,
                fontSize = 9.sp,
                maxLines = 2,
                modifier = Modifier.settingsCardWidth().padding(vertical = 2.dp),
            )
        }
        hosts.forEach { device ->
            val addr = runCatching { device.address }.getOrNull()
            val isConnected = addr != null && addr == connectedAddr
            val isSwitching = addr != null && addr == switchingAddr && !isConnected
            val isPreferred = addr != null && addr == activeAddr
            val hint = when {
                isConnected -> stringResource(R.string.set_hid_host_connected)
                isSwitching -> stringResource(R.string.set_hid_host_switching)
                isPreferred -> stringResource(R.string.set_hid_host_selected)
                else -> stringResource(R.string.set_hid_tap_switch)
            }
            HostRow(
                label = bluetooth?.hostLabel(device) ?: addr.orEmpty(),
                hint = hint,
                connected = isConnected,
                accent = isConnected || isPreferred || isSwitching,
            ) {
                haptics.tick()
                bluetooth?.connectTo(device)
            }
        }
        if (bluetooth != null && hasPermission) {
            ActionButton(
                text = stringResource(R.string.set_hid_refresh_hosts),
                modifier = Modifier.settingsCardWidth().padding(top = 3.dp),
                onClick = {
                    haptics.tick()
                    bluetooth.ensureProxy()
                    refreshNonce++
                },
            )
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

        SectionLabel(stringResource(R.string.set_hid_external_config))
        Text(
            text = stringResource(
                R.string.set_hid_adb_push,
                externalPath ?: stringResource(R.string.set_hid_no_external_dir),
            ),
            fontFamily = FontFamily.Monospace,
            color = palette.primary,
            fontSize = 8.5.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.settingsCardWidth(),
        )
    }
}

/** Paired-host row: status indicator, monospace name, tap to switch host cleanly. */
@Composable
private fun HostRow(
    label: String,
    hint: String,
    connected: Boolean = false,
    accent: Boolean = false,
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
        if (accent && !pressed) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 7.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(listOf(palette.primary, palette.primary.copy(alpha = 0.15f))),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 11.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        when {
                            pressed -> palette.onPrimary
                            connected -> palette.primary
                            accent -> palette.secondary.copy(alpha = 0.65f)
                            else -> palette.muted.copy(alpha = 0.4f)
                        },
                        CircleShape,
                    ),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        pressed -> palette.onPrimary
                        accent -> palette.primary
                        else -> palette.text
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hint.isNotEmpty()) {
                    Text(
                        text = hint,
                        color = if (pressed) palette.onPrimary.copy(alpha = 0.72f) else palette.muted,
                        fontSize = 8.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
