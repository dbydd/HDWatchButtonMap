package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.input.Symbol

/**
 * 输入日志屏：实时 report / 事件流（初值取 history 尾部），自动滚到底，
 * 底部虚拟输入条可在模拟器上把符号喂进引擎，验证全链路。
 */

private const val LOG_TAIL = 80
private const val LOG_KEEP = 200

@Composable
fun LogScreen() {
    val app = HdApp.instance
    val palette = LocalHdPalette.current
    val lines = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        lines.clear()
        lines.addAll(app.ring.history.takeLast(LOG_TAIL))
        app.ring.recent.collect { line ->
            lines.add(line)
            while (lines.size > LOG_KEEP) lines.removeAt(0)
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState) {
        // maxValue only changes when the measured content height changes, i.e. on
        // every new line, so this tracks the tail without polling frames.
        snapshotFlow { scrollState.maxValue }.collect { max ->
            if (max > 0) scrollState.scrollTo(max)
        }
    }

    ScreenScaffold(title = "输入日志") {
        SectionLabel("报告 / 事件")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .verticalScroll(scrollState),
        ) {
            if (lines.isEmpty()) {
                Text("暂无日志", color = palette.muted, fontSize = 9.sp)
            }
            lines.forEach { line ->
                Text(
                    text = line,
                    color = palette.muted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        SectionLabel("虚拟输入")
        listOf(0, 4).forEach { start ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Symbol.mappable.drop(start).take(4).forEach { sym ->
                    ActionButton(
                        text = "${sym.display} ${sym.label}",
                        onClick = { app.engine.feed(sym) },
                        modifier = Modifier.weight(1f),
                        tone = HdTone.Accent,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(
                text = "清空日志",
                onClick = {
                    app.ring.clear()
                    lines.clear()
                },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                text = "紧急释放",
                onClick = { app.runner.emergencyRelease() },
                modifier = Modifier.weight(1f),
                tone = HdTone.Danger,
            )
        }
    }
}
