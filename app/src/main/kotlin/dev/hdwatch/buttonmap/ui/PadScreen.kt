package dev.hdwatch.buttonmap.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.engine.EngineEvent
import dev.hdwatch.buttonmap.hid.TransportKind
import dev.hdwatch.buttonmap.hid.TransportStatus
import dev.hdwatch.buttonmap.input.Symbol
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The pad as a full Orokin dial: a textured watch face (sunburst, tick rings,
 * dot ring) drawn behind everything, a double-ringed focus card at the hub,
 * four diamond cells on the cardinal axes, and the eight secondary symbols on
 * a live ring between them — every mark on this dial is also an input.
 */
@Composable
fun PadScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current

    val config by app.configRepo.config.collectAsState()
    val buffer by app.engine.bufferState.collectAsState()
    val event by app.engine.event.collectAsState()
    val transport = remember(config.settings.forceLoggingTransport) {
        app.transports.forSettings(config.settings)
    }
    val transportStatus by transport.status.collectAsState()

    val profile = config.activeProfile

    // A different profile has a different macro table: never carry a
    // half-entered sequence across a switch.
    LaunchedEffect(config.activeProfileId) { app.engine.reset() }

    // Ignition: the card halo flares when an action fires.
    var pulse by remember { mutableStateOf(0) }
    LaunchedEffect(event) {
        if (event is EngineEvent.FiredMacro || event is EngineEvent.FiredSingle) pulse++
    }
    var hot by remember { mutableStateOf(false) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        repeat(2) {
            hot = true
            delay(110)
            hot = false
            delay(110)
        }
    }
    val flare by animateFloatAsState(
        targetValue = if (hot) 1f else 0f,
        animationSpec = tween(if (hot) 60 else 220),
        label = "flare",
    )

    fun feed(sym: Symbol) {
        haptics.press()
        app.engine.feed(sym)
    }

    // Which ring symbols currently do something (mapping or sequence start).
    val liveSymbols = remember(profile) {
        buildSet {
            profile.single.keys.forEach { add(it) }
            profile.macros.filter { it.enabled }.forEach { m -> m.sequence.firstOrNull()?.let { add(it) } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.edge),
    ) {
        // ---- dial face: pure decoration, no hit targets here ----
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val R = size.minDimension / 2f
            fun at(angleDeg: Double, radius: Float) = Offset(
                cx + (radius * sin(angleDeg)).toFloat(),
                cy - (radius * cos(angleDeg)).toFloat(),
            )

            // Domed field.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.surface, palette.background, palette.edge),
                    center = Offset(cx, cy),
                    radius = R,
                ),
                radius = R,
                center = Offset(cx, cy),
            )
            // Sunburst rays: long bright every 15°, faint short between.
            var i = 0
            while (i < 72) {
                val a = i * 5.0
                val major = i % 3 == 0
                drawLine(
                    color = palette.primary.copy(alpha = if (major) 0.17f else 0.07f),
                    start = at(a, R * if (major) 0.46f else 0.50f),
                    end = at(a, R * if (major) 0.99f else 0.94f),
                    strokeWidth = if (major) 1.4f else 1f,
                )
                i++
            }
            // Structural rings.
            drawCircle(
                color = palette.primary.copy(alpha = 0.24f + 0.25f * flare),
                radius = R * 0.965f,
                center = Offset(cx, cy),
                style = Stroke(1.5f),
            )
            drawCircle(
                color = palette.primary.copy(alpha = 0.10f),
                radius = R * 0.90f,
                center = Offset(cx, cy),
                style = Stroke(1f),
            )
            drawCircle(
                color = palette.primary.copy(alpha = 0.30f),
                radius = R * 0.56f,
                center = Offset(cx, cy),
                style = Stroke(1f),
            )
            // Outer minute ticks, 24 of them, skipping the four cell axes.
            i = 0
            while (i < 24) {
                val a = i * 15.0
                if (i % 6 != 0) {
                    drawLine(
                        palette.primary.copy(alpha = 0.60f),
                        at(a, R * 0.915f),
                        at(a, R * 0.955f),
                        2f,
                    )
                }
                i++
            }
            // Inner fine tick ring between symbol ring and card.
            i = 0
            while (i < 48) {
                val a = i * 7.5
                val major = i % 4 == 0
                drawLine(
                    palette.secondary.copy(alpha = if (major) 0.35f else 0.14f),
                    at(a, R * if (major) 0.60f else 0.62f),
                    at(a, R * 0.655f),
                    if (major) 1.6f else 1f,
                )
                i++
            }
            // Dot ring filling the band outside the diamond cells.
            i = 0
            while (i < 36) {
                val a = i * 10.0
                drawCircle(
                    palette.primary.copy(alpha = if (i % 3 == 0) 0.35f else 0.12f),
                    radius = if (i % 3 == 0) 1.6f else 1f,
                    center = at(a, R * 0.765f),
                )
                i++
            }
        }

        // ---- symbol ring: 8 secondary inputs at the diagonal offsets ----
        val ringSymbols = listOf(
            Symbol.CROWN_CW, Symbol.STEM, Symbol.CROWN_CCW, Symbol.STEM_LONG,
            Symbol.GESTURE_1, Symbol.GESTURE_2, Symbol.GESTURE_3, Symbol.GESTURE_4,
        )
        ringSymbols.forEachIndexed { idx, sym ->
            RingSymbol(
                symbol = sym,
                active = sym in liveSymbols,
                angleDeg = 22.5 + idx * 45.0,
                radiusFrac = 0.55f,
                modifier = Modifier.align(Alignment.Center),
            ) { feed(sym) }
        }

        // ---- four diamond cells on the cardinal axes ----
        DiamondCell(
            symbol = Symbol.UP,
            action = profile.single[Symbol.UP],
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-78).dp),
        ) { feed(Symbol.UP) }
        DiamondCell(
            symbol = Symbol.DOWN,
            action = profile.single[Symbol.DOWN],
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 78.dp),
        ) { feed(Symbol.DOWN) }
        DiamondCell(
            symbol = Symbol.LEFT,
            action = profile.single[Symbol.LEFT],
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-78).dp),
        ) { feed(Symbol.LEFT) }
        DiamondCell(
            symbol = Symbol.RIGHT,
            action = profile.single[Symbol.RIGHT],
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 78.dp),
        ) { feed(Symbol.RIGHT) }

        // ---- hub: double-ringed focus card ----
        FocusCard(
            name = profile.name,
            buffer = buffer,
            event = event,
            link = linkCode(transportStatus),
            flare = flare,
            modifier = Modifier
                .align(Alignment.Center)
                .size(104.dp),
            onCycle = {
                haptics.tick()
                app.configRepo.cycleActiveProfile()
            },
            onMenu = { nav.push(Route.Menu) },
        )
    }
}

/** One radial symbol on the live ring; tap feeds it straight into the engine. */
@Composable
private fun RingSymbol(
    symbol: Symbol,
    active: Boolean,
    angleDeg: Double,
    radiusFrac: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    // 0° = up; place by trigonometry, orient tangentially, keep bottom legible.
    val dx = kotlin.math.sin(Math.toRadians(angleDeg)).toFloat()
    val dy = -kotlin.math.cos(Math.toRadians(angleDeg)).toFloat()
    Text(
        text = symbol.code,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = 1.sp,
        color = if (active) palette.primary.copy(alpha = 0.9f) else palette.muted.copy(alpha = 0.7f),
        maxLines = 1,
        modifier = modifier
            .offset(x = (dx * radiusFrac * 113f).dp, y = (dy * radiusFrac * 113f).dp)
            .graphicsLayer {
                rotationZ = if (angleDeg <= 180) (angleDeg + 90).toFloat() else (angleDeg - 90).toFloat()
            }
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

/** A direction key as a rotated gold-rimmed square; content stays upright. */
@Composable
private fun DiamondCell(
    symbol: Symbol,
    action: Step?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val key = summarize(action).let { if (it == "—") "·" else it }

    Box(modifier.size(44.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = 45f }
                .background(
                    if (pressed) {
                        Brush.verticalGradient(
                            listOf(
                                androidx.compose.ui.graphics.lerp(palette.primary, Color.White, 0.35f),
                                palette.primary,
                                palette.goldDeep,
                            ),
                        )
                    } else {
                        Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom))
                    },
                    RoundedCornerShape(6.dp),
                )
                .border(
                    width = if (pressed) 1.4.dp else 1.dp,
                    brush = if (pressed) {
                        Brush.verticalGradient(listOf(palette.goldDeep, palette.goldDeep))
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.22f),
                                palette.hairline,
                                palette.primary.copy(alpha = 0.30f),
                            ),
                        )
                    },
                    shape = RoundedCornerShape(6.dp),
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = symbol.display,
                color = if (pressed) palette.onPrimary else palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = key,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                color = if (pressed) palette.onPrimary.copy(alpha = 0.75f) else palette.secondary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The double-ringed hub card: identity, live sequence, mode switch. */
@Composable
private fun FocusCard(
    name: String,
    buffer: List<Symbol>,
    event: EngineEvent,
    link: String,
    flare: Float,
    onCycle: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            palette.primary.copy(alpha = 0.05f + 0.20f * flare),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
                .background(
                    Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom)),
                    CircleShape,
                )
                .border(
                    1.2.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            palette.hairline,
                            palette.primary.copy(alpha = 0.35f + 0.4f * flare),
                        ),
                    ),
                    CircleShape,
                )
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onCycle,
                    onLongClick = onMenu,
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = name,
                    style = TextStyle(
                        brush = goldTextBrush(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hairline(modifier = Modifier.width(14.dp))
                    Box(
                        Modifier
                            .size(3.dp)
                            .background(palette.primary.copy(alpha = 0.8f))
                            .graphicsLayer { rotationZ = 45f },
                    )
                    Hairline(modifier = Modifier.width(14.dp))
                }
                Spacer(Modifier.height(2.dp))
                // One live line: sequence while entering, last action after.
                if (buffer.isNotEmpty()) {
                    Text(
                        text = buffer.joinToString(" ") { it.display },
                        fontFamily = FontFamily.Monospace,
                        color = palette.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = eventLine(event),
                        color = when (event) {
                            is EngineEvent.FiredMacro -> palette.primary
                            is EngineEvent.FiredSingle -> palette.secondary
                            is EngineEvent.Unmapped -> palette.danger
                            else -> palette.secondary.copy(alpha = 0.75f)
                        },
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f))
                MicroLabel(
                    text = link,
                    color = palette.muted,
                    modifier = Modifier.padding(bottom = 11.dp),
                )
            }
        }
    }
}

/** Compact transport code for the hub footer. */
private fun linkCode(s: TransportStatus): String = when {
    s.kind == TransportKind.LOGGING -> "LINK · SIM"
    s.label.contains("已连接") -> "LINK · HOST"
    s.label.contains("缺少权限") -> "LINK · AUTH"
    s.label.contains("不可用") || s.label.contains("失败") -> "LINK · DOWN"
    else -> "LINK · IDLE"
}

private fun eventLine(event: EngineEvent): String = when (event) {
    is EngineEvent.FiredMacro -> "激活「${event.macro.name}」"
    is EngineEvent.FiredSingle -> "${event.symbol.display} ${summarize(event.step)}"
    is EngineEvent.Unmapped -> "未映射 ${event.symbol.display}"
    is EngineEvent.Pending -> "序列中 ${event.buffer.joinToString(" ") { it.display }}"
    EngineEvent.SequenceTimeout -> "序列超时清空"
    EngineEvent.Idle -> "待命"
}
