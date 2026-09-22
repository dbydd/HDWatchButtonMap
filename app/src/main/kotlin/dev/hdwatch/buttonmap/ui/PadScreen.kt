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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.engine.EngineEvent
import dev.hdwatch.buttonmap.hid.TransportKind
import dev.hdwatch.buttonmap.hid.TransportStatus
import dev.hdwatch.buttonmap.input.Symbol
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The pad as a full Orokin dial: painted sunburst/tick/dot rings behind a
 * square hub plaque (keys mode shows its map, macro mode its sequence list),
 * four arc-cut keycaps riding the rim, and the eight secondary symbols on a
 * live ring between them.
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

    // Ignition: the hub halo flares when an action fires.
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
    // Which keycap is under the finger (drives the gold flood in the Canvas).
    var pressedDir by remember { mutableStateOf<ArcDir?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.edge),
    ) {
        // ---- dial face: pure decoration ----
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val R = size.minDimension / 2f
            fun at(angleDeg: Double, radius: Float) = Offset(
                cx + (radius * sin(angleDeg)).toFloat(),
                cy - (radius * cos(angleDeg)).toFloat(),
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.surface, palette.background, palette.edge),
                    center = Offset(cx, cy),
                    radius = R,
                ),
                radius = R,
                center = Offset(cx, cy),
            )
            // Sunburst rays.
            var i = 0
            while (i < 72) {
                val a = i * 5.0
                val major = i % 3 == 0
                drawLine(
                    color = palette.primary.copy(alpha = if (major) 0.17f else 0.07f),
                    start = at(a, R * if (major) 0.53f else 0.56f),
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
                radius = R * 0.885f,
                center = Offset(cx, cy),
                style = Stroke(1f),
            )
            // Outer minute ticks, skipping the four keycap axes.
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
            // Dot ring under the text pairs in the diagonal gaps.
            i = 0
            while (i < 36) {
                val a = i * 10.0
                drawCircle(
                    palette.primary.copy(alpha = if (i % 3 == 0) 0.35f else 0.12f),
                    radius = if (i % 3 == 0) 1.6f else 1f,
                    center = at(a, R * 0.735f),
                )
                i++
            }
            // ---- keycap sectors: true annular fans at real radii, so the
            // painted band always spans inner 56dp → outer 104dp exactly ----
            val rIn = R * 0.4956f
            val rOut = R * 0.9204f
            val half = 24f
            ArcDir.entries.forEach { dir ->
                val start = dir.dialAngle - 90f - half
                val outerRect = Rect(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
                val innerRect = Rect(cx - rIn, cy - rIn, cx + rIn, cy + rIn)
                val fan = Path().apply {
                    arcTo(outerRect, start, half * 2f, true)
                    arcTo(innerRect, start + half * 2f, -half * 2f, false)
                    close()
                }
                if (pressedDir == dir) {
                    drawPath(
                        fan,
                        brush = Brush.linearGradient(
                            listOf(
                                androidx.compose.ui.graphics.lerp(palette.primary, Color.White, 0.35f),
                                palette.primary,
                                palette.goldDeep,
                            ),
                        ),
                    )
                } else {
                    drawPath(
                        fan,
                        brush = Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom)),
                        alpha = 0.97f,
                    )
                }
                drawPath(
                    fan,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.26f), palette.primary.copy(alpha = 0.38f)),
                    ),
                    style = Stroke(2f),
                )
            }
        }

        // ---- live symbol ring: bare text pairs in the diagonal gaps ----
        val ringPairs = listOf(
            45.0 to (Symbol.CROWN_CW to Symbol.CROWN_CCW),
            135.0 to (Symbol.STEM to Symbol.STEM_LONG),
            225.0 to (Symbol.GESTURE_1 to Symbol.GESTURE_2),
            315.0 to (Symbol.GESTURE_3 to Symbol.GESTURE_4),
        )
        ringPairs.forEach { (center, pair) ->
            // Keep clear of the keycap angular footprint: hug the diagonal.
            listOf(pair.first to -7.0, pair.second to 7.0).forEach { (sym, off) ->
                RingSymbol(
                    symbol = sym,
                    active = sym in liveSymbols,
                    angleDeg = center + off,
                    radiusDp = 83f,
                    modifier = Modifier.align(Alignment.Center),
                ) { feed(sym) }
            }
        }

        // ---- four keycap touch pads (the caps themselves are painted) ----
        ArrowCap(
            symbol = Symbol.UP,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-80).dp)
                .size(width = 66.dp, height = 48.dp),
            onPressedChange = { pressedDir = if (it) ArcDir.UP else null },
        ) { feed(Symbol.UP) }
        ArrowCap(
            symbol = Symbol.DOWN,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 80.dp)
                .size(width = 66.dp, height = 48.dp),
            onPressedChange = { pressedDir = if (it) ArcDir.DOWN else null },
        ) { feed(Symbol.DOWN) }
        ArrowCap(
            symbol = Symbol.LEFT,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-80).dp)
                .size(width = 48.dp, height = 66.dp),
            onPressedChange = { pressedDir = if (it) ArcDir.LEFT else null },
        ) { feed(Symbol.LEFT) }
        ArrowCap(
            symbol = Symbol.RIGHT,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 80.dp)
                .size(width = 48.dp, height = 66.dp),
            onPressedChange = { pressedDir = if (it) ArcDir.RIGHT else null },
        ) { feed(Symbol.RIGHT) }

        // ---- hub plaque ----
        HubCard(
            profile = profile,
            buffer = buffer,
            event = event,
            link = linkCode(transportStatus),
            flare = flare,
            modifier = Modifier
                .align(Alignment.Center)
                .size(108.dp),
            onCycle = {
                haptics.tick()
                app.configRepo.cycleActiveProfile()
            },
            onMenu = { nav.push(Route.Menu) },
        )
    }
}

/** Bare text input on the live ring: tangential glyph, gold when bound. */
@Composable
private fun RingSymbol(
    symbol: Symbol,
    active: Boolean,
    angleDeg: Double,
    radiusDp: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val dx = sin(Math.toRadians(angleDeg)).toFloat()
    val dy = -cos(Math.toRadians(angleDeg)).toFloat()
    val label = symbol.display ?: symbol.code
    Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = if (label.length > 1) 9.sp else 11.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) palette.primary.copy(alpha = 0.95f) else palette.muted.copy(alpha = 0.8f),
        maxLines = 1,
        modifier = modifier
            .offset(x = (dx * radiusDp).dp, y = (dy * radiusDp).dp)
            .graphicsLayer {
                rotationZ = if (angleDeg <= 180) (angleDeg + 90).toFloat() else (angleDeg - 90).toFloat()
            }
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 5.dp, vertical = 4.dp),
    )
}

/** Dial bearing of each keycap, 0° = up, clockwise. */
private enum class ArcDir(val dialAngle: Float) {
    UP(0f), RIGHT(90f), DOWN(180f), LEFT(270f),
}

/**
 * Transparent touch pad laid over one painted keycap sector: it forwards the
 * press state to the dial Canvas (gold flood) and hosts the arrow glyph.
 */
@Composable
private fun ArrowCap(
    symbol: Symbol,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    Box(
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol.display,
            color = if (pressed) palette.onPrimary else palette.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The octagonal hub medallion: outer octagon ring + glass octagon panel.
 * Keys mode shows its compact map, macro mode the enabled sequence list.
 */
@Composable
private fun HubCard(
    profile: dev.hdwatch.buttonmap.config.Profile,
    buffer: List<Symbol>,
    event: EngineEvent,
    link: String,
    flare: Float,
    onCycle: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    val outerShape = remember(modifier) { OctagonShape(7.dp) }
    val innerShape = remember(modifier) { OctagonShape(5.dp) }
    Box(modifier) {
        // Outer octagon hairline ring.
        Box(
            Modifier
                .fillMaxSize()
                .border(1.dp, palette.primary.copy(alpha = 0.30f + 0.3f * flare), outerShape),
        )
        // Panel.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(4.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            androidx.compose.ui.graphics.lerp(palette.panelTop, palette.primary, 0.05f + 0.18f * flare),
                            palette.panelBottom,
                        ),
                    ),
                    innerShape,
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
                    innerShape,
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
                    .padding(horizontal = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(6.dp))
                // Keys mode prints its name at the smallest size on the rim;
                // macro mode shows no title at all — the list speaks itself.
                if (profile.kind != ProfileKind.MACRO) {
                    Text(
                        text = profile.name,
                        fontSize = 4.sp,
                        letterSpacing = 1.5.sp,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (buffer.isNotEmpty()) {
                    // Live sequence takes the stage.
                    Text(
                        text = buffer.joinToString(" ") { it.display },
                        fontFamily = FontFamily.Monospace,
                        color = palette.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                } else if (profile.kind == ProfileKind.MACRO) {
                    // Macro mode: the usable sequences, nothing else.
                    val macros = profile.macros.filter { it.enabled }
                    if (macros.isEmpty()) {
                        Text("无启用宏", color = palette.muted, fontSize = 9.sp, maxLines = 1)
                    } else {
                        macros.take(4).forEach { m ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = m.name,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (m == macros.first()) palette.primary else palette.primary.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text(
                                    text = m.sequence.joinToString("") { it.display ?: it.code },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 7.5.sp,
                                    color = palette.secondary.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    // Silence when idle: the dial needs no "standby" caption.
                    if (event != EngineEvent.Idle) {
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
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = link,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 4.sp,
                    letterSpacing = 1.5.sp,
                    color = palette.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp),
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

