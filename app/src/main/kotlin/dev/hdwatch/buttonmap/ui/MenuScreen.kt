package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp

/** Shared row look for every menu-style screen (stub-grade until screens land). */
@Composable
fun ScreenScaffold(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = LocalHdPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .padding(top = 8.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = palette.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
        Spacer(Modifier.height(46.dp))
    }
}

@Composable
fun MenuRow(label: String, hint: String = "", onClick: () -> Unit) {
    val palette = LocalHdPalette.current
    Column(
        modifier = Modifier
            .widthIn(min = 140.dp, max = 206.dp)
            .padding(vertical = 2.dp)
            .background(palette.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
            .border(
                1.dp,
                palette.primary.copy(alpha = 0.30f),
                androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (hint.isNotEmpty()) {
            Text(hint, color = palette.muted, fontSize = 10.sp, maxLines = 2)
        }
    }
}

@Composable
fun MenuScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val actions = LocalPlatformActions.current
    val config by app.configRepo.config.collectAsState()
    val transport = app.transports.forSettings(config.settings)
    val status by transport.status.collectAsState()
    val repoStatus by app.configRepo.status.collectAsState()

    ScreenScaffold(title = "HD MAP") {
        MenuRow("单键映射", "点按/按键/表冠/手势 → HID") { nav.push(Route.MappingList) }
        MenuRow("宏命令", "${config.macros.count { it.enabled }}/${config.macros.size} 启用") { nav.push(Route.MacroList) }
        MenuRow("蓝牙 HID", status.label + " · " + status.detail) { nav.push(Route.Hid) }
        MenuRow("设置 · 传感器 · 调试") { nav.push(Route.Settings) }
        MenuRow("输入日志") { nav.push(Route.Log) }
        MenuRow("重载配置", repoStatus) { app.configRepo.reload() }
        MenuRow("从文件导入 JSON") { actions.importFile() }
    }
}
