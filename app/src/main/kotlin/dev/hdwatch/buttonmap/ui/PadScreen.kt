package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.engine.EngineEvent
import dev.hdwatch.buttonmap.hid.TransportKind
import dev.hdwatch.buttonmap.input.Symbol
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The remote face.
 *
 * Layout: a large square hub owns the middle — it is the information panel
 * (pending sequence HUD, last event, transport state, compact single-key
 * map, menu affordance). The four direction buttons are slim edge bands
 * hugging the round screen border: a flat inner side against the hub and a
 * concave circular outer edge, sized only for thumb contact.
 *
 * Geometry convention (canvas coords): 0°=east, angles grow clockwise;
 * square half-side s = 0.58R, hub corners sit at 45° diagonals.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun PadScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val palette = LocalHdPalette.current
    val config by app.configRepo.config.collectAsState()
    val buffer by app.engine.bufferState.collectAsState()
    val event by app.engine.event.collectAsState()
    val transport = remember(config.settings.forceLoggingTransport) {
        app.transports.forSettings(config.settings)
    }
    val transportStatus by transport.status.collectAsState()

    val measurer = rememberTextMeasurer()
    var pressed by remember { mutableStateOf<Symbol?>(null) }

    fun feedOrMenu(offset: Offset, w: Float, h: Float): Symbol? {
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(cx, cy)
        val s = radius * HUB_HALF
        val dx = offset.x - cx
        val dy = offset.y - cy
        if (abs(dx) <= s && abs(dy) <= s) return Symbol.STEM // hub sentinel
        if (hypot(dx, dy) > radius) return null
        return if (abs(dy) >= abs(dx)) {
            if (dy < 0) Symbol.UP else Symbol.DOWN
        } else {
            if (dx < 0) Symbol.LEFT else Symbol.RIGHT
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .pointerInput(config) {
                detectTapGestures(
                    onPress = { off ->
                        pressed = feedOrMenu(off, size.width.toFloat(), size.height.toFloat())
                        tryAwaitRelease()
                        pressed = null
                    },
                    onTap = { off ->
                        when (val zone = feedOrMenu(off, size.width.toFloat(), size.height.toFloat())) {
                            null -> Unit
                            Symbol.STEM -> nav.push(Route.Menu)
                            else -> app.engine.feed(zone)
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = min(cx, cy)
            val s = radius * HUB_HALF

            // ---- four slim edge buttons (flat inner side, concave outer arc) ----
            fun band(symbol: Symbol, baseDeg: Float) {
                drawBand(
                    measurer, palette, symbol,
                    pressed = pressed == symbol,
                    cx = cx, cy = cy, radius = radius, hubHalf = s, baseDeg = baseDeg,
                )
            }
            band(Symbol.UP, 225f)   // hub corner angles: up edge 225°..315°
            band(Symbol.RIGHT, 315f)
            band(Symbol.DOWN, 45f)
            band(Symbol.LEFT, 135f)

            // ---- hub: the big information square ----
            drawRoundRect(
                color = if (pressed == Symbol.STEM) palette.primary.copy(alpha = 0.18f) else palette.surface,
                topLeft = Offset(cx - s, cy - s),
                size = androidx.compose.ui.geometry.Size(s * 2f, s * 2f),
                cornerRadius = CornerRadius(s * 0.14f),
            )
            drawRoundRect(
                color = palette.primary.copy(alpha = 0.85f),
                topLeft = Offset(cx - s, cy - s),
                size = androidx.compose.ui.geometry.Size(s * 2f, s * 2f),
                cornerRadius = CornerRadius(s * 0.14f),
                style = Stroke(width = 2.5.dp.toPx()),
            )

            val single = config.single
            val line1 = "HD MAP"
            val line2 = if (buffer.isNotEmpty()) buffer.joinToString(" ") { it.display } else "点中心开菜单"
            val line3 = when (event) {
                is EngineEvent.FiredMacro -> "激活「${(event as EngineEvent.FiredMacro).macro.name}」"
                is EngineEvent.FiredSingle -> {
                    val e = event as EngineEvent.FiredSingle
                    "${e.symbol.display} ${summarize(e.step)}"
                }
                is EngineEvent.Unmapped -> "未映射 ${(event as EngineEvent.Unmapped).symbol.display}"
                EngineEvent.SequenceTimeout -> "序列已清空"
                else -> summarizeStatus(transportStatus)
            }
            val line4 = compactSingleMap(single)

            drawLabel(measurer, line1, cx, cy - s * 0.62f, radius * 0.10f, palette.primary)
            drawLabel(
                measurer, line2, cx, cy - s * 0.16f,
                radius * (if (buffer.isNotEmpty()) 0.155f else 0.095f),
                if (buffer.isNotEmpty()) palette.secondary else palette.muted,
            )
            drawLabel(measurer, line3, cx, cy + s * 0.32f, radius * 0.082f, palette.text)
            drawLabel(measurer, line4, cx, cy + s * 0.62f, radius * 0.075f, palette.muted)
        }
    }
}

private fun summarizeStatus(s: dev.hdwatch.buttonmap.hid.TransportStatus): String = when {
    s.kind == TransportKind.LOGGING -> "日志模拟传输"
    s.label.contains("已连接") -> "HID→${s.hostName ?: "主机"}"
    s.label.contains("缺少权限") -> "待授予蓝牙权限"
    s.label.contains("不可用") || s.label.contains("失败") -> "HID不可用:${s.label}"
    else -> "HID待配对"
}

private fun compactSingleMap(single: Map<Symbol, dev.hdwatch.buttonmap.config.Step>): String {
    fun q(sym: Symbol) = summarize(single[sym]).let { if (it == "—") "?" else it }
    return "↑${q(Symbol.UP)} ↓${q(Symbol.DOWN)} ←${q(Symbol.LEFT)} →${q(Symbol.RIGHT)}"
}

/**
 * Draws one edge band: hub corner A -> hub corner B straight (flat inner
 * side), circular arc B->A through the side midpoint (concave outer edge).
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawBand(
    measurer: TextMeasurer,
    palette: HdPalette,
    symbol: Symbol,
    pressed: Boolean,
    cx: Float,
    cy: Float,
    radius: Float,
    hubHalf: Float,
    baseDeg: Float,
) {
    val cornerDist = hubHalf * 1.41421356f
    val endDeg = baseDeg + 90f
    val path = Path().apply {
        moveTo(cx + cos(rad(baseDeg)) * cornerDist, cy + sin(rad(baseDeg)) * cornerDist)
        lineTo(cx + cos(rad(endDeg)) * cornerDist, cy + sin(rad(endDeg)) * cornerDist)
        val steps = 10
        for (i in 1..steps) {
            val a = endDeg - 90f * i / steps
            lineTo(cx + cos(rad(a)) * radius, cy + sin(rad(a)) * radius)
        }
        close()
    }
    if (pressed) {
        drawPath(path, color = palette.primary.copy(alpha = 0.42f))
    }
    drawPath(path, color = palette.primary.copy(alpha = if (pressed) 1f else 0.65f), style = Stroke(width = 1.6.dp.toPx()))

    val midDeg = baseDeg + 45f
    // glyph sits in the middle of the band, safely inside the screen circle
    val gx = cx + cos(rad(midDeg)) * radius * 0.815f
    val gy = cy + sin(rad(midDeg)) * radius * 0.815f
    drawLabel(measurer, symbol.display ?: symbol.code, gx, gy, radius * 0.085f, palette.text)
}

private fun rad(deg: Float): Float = deg * 0.017453292f

private const val HUB_HALF = 0.58f

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    cx: Float,
    cy: Float,
    fontPx: Float,
    color: Color,
    cache: MutableMap<Pair<String, Int>, TextLayoutResult>? = null,
) {
    val key = text to fontPx.toInt()
    val layout = cache?.getOrPut(key) {
        measurer.measure(
            text,
            TextStyle(fontSize = fontPx.coerceAtLeast(9f).sp, fontWeight = FontWeight.Bold, color = color),
        )
    } ?: measurer.measure(
        text,
        TextStyle(fontSize = fontPx.coerceAtLeast(9f).sp, fontWeight = FontWeight.Bold, color = color),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
    )
}
