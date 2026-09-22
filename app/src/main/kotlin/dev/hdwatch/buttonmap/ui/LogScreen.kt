package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.input.Symbol

/**
 * 输入日志屏：玻璃面板里的 report / 事件流（初值取 history 尾部，自动滚到底），
 * 底部虚拟输入键帽可在模拟器上把符号喂进引擎，验证全链路。
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
        HdPanel(
            modifier = Modifier
                .widthIn(min = 140.dp, max = 200.dp)
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            ) {
                if (lines.isEmpty()) {
                    Text("暂无日志", color = palette.muted, fontSize = 9.sp, maxLines = 1)
                }
                lines.forEach { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        color = if (isStatusError(line)) palette.danger else palette.muted,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SectionLabel("虚拟输入")
        Symbol.mappable.chunked(4).forEach { rowSymbols ->
            Row(
                modifier = Modifier
                    .widthIn(min = 132.dp, max = 168.dp)
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                rowSymbols.forEach { sym ->
                    KeycapButton(sym, Modifier.weight(1f)) { app.engine.feed(sym) }
                }
            }
        }

        Row(
            modifier = Modifier
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionButton(
                text = "清空日志",
                onClick = {
                    app.ring.clear()
                    lines.clear()
                },
            )
            ActionButton(
                text = "紧急释放",
                onClick = { app.runner.emergencyRelease() },
                tone = HdTone.Danger,
            )
        }
    }
}

/** Virtual-input keycap: symbol glyph over its stable code, gold flood on press. */
@Composable
private fun KeycapButton(
    symbol: Symbol,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(7.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            Text(
                text = symbol.display,
                fontFamily = FontFamily.Monospace,
                color = if (pressed) palette.onPrimary else palette.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
            Text(
                text = symbol.code,
                fontFamily = FontFamily.Monospace,
                color = if (pressed) palette.onPrimary.copy(alpha = 0.7f) else palette.secondary.copy(alpha = 0.85f),
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
            )
        }
    }
}
