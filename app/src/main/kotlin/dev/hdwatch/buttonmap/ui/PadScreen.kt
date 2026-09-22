package dev.hdwatch.buttonmap.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay

/**
 * The pad, Orokin console cut: a double-ringed circular focus card at the
 * dial center, four diamond cells on the cardinal axes, gold ornaments in the
 * free corners. Everything radial — no square block wasting the round screen.
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

    // Ignition: the card's halo ring flares when an action fires.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(palette.surface, palette.background, palette.edge),
                ),
            ),
    ) {
        FocusCard(
            name = profile.name,
            buffer = buffer,
            event = event,
            single = profile.single,
            flare = flare,
            link = linkCode(transportStatus),
            modifier = Modifier
                .align(Alignment.Center)
                .size(124.dp),
            onCycle = {
                haptics.tick()
                app.configRepo.cycleActiveProfile()
            },
            onMenu = { nav.push(Route.Menu) },
        )

        DiamondCell(
            symbol = Symbol.UP,
            action = profile.single[Symbol.UP],
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 5.dp)
                .size(46.dp),
        ) {
            haptics.press()
            app.engine.feed(Symbol.UP)
        }
        DiamondCell(
            symbol = Symbol.DOWN,
            action = profile.single[Symbol.DOWN],
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp)
                .size(46.dp),
        ) {
            haptics.press()
            app.engine.feed(Symbol.DOWN)
        }
        DiamondCell(
            symbol = Symbol.LEFT,
            action = profile.single[Symbol.LEFT],
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp)
                .size(46.dp),
        ) {
            haptics.press()
            app.engine.feed(Symbol.LEFT)
        }
        DiamondCell(
            symbol = Symbol.RIGHT,
            action = profile.single[Symbol.RIGHT],
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .size(46.dp),
        ) {
            haptics.press()
            app.engine.feed(Symbol.RIGHT)
        }

        // Ornaments: tiny gold diamonds settling the free corners.
        OrnamentDiamond(Modifier.align(Alignment.TopStart).padding(start = 42.dp, top = 44.dp))
        OrnamentDiamond(Modifier.align(Alignment.TopEnd).padding(end = 42.dp, top = 44.dp))
        OrnamentDiamond(Modifier.align(Alignment.BottomStart).padding(start = 42.dp, bottom = 44.dp))
        OrnamentDiamond(Modifier.align(Alignment.BottomEnd).padding(end = 42.dp, bottom = 44.dp))

        // (link readout lives in the card header now; the lower crescent is
        // reserved for the DOWN diamond.)
    }
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

    Box(modifier) {
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
                                palette.primary.copy(alpha = 0.25f),
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
                color = if (pressed) palette.onPrimary.copy(alpha = 0.75f) else palette.secondary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The double-ringed circular focus card: identity, live sequence, mode switch. */
@Composable
private fun FocusCard(
    name: String,
    buffer: List<Symbol>,
    event: EngineEvent,
    single: Map<Symbol, Step>,
    flare: Float,
    link: String,
    onCycle: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    Box(modifier) {
        // Outer hairline ring.
        Box(
            Modifier
                .fillMaxSize()
                .border(1.dp, palette.primary.copy(alpha = 0.30f + 0.30f * flare), CircleShape),
        )
        // Halo between the rings, driven by the ignition flare.
        Box(
            Modifier
                .fillMaxSize()
                .padding(2.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.Transparent,
                            palette.primary.copy(alpha = 0.06f + 0.22f * flare),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        // Inner panel.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(116.dp)
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
                            palette.primary.copy(alpha = 0.30f),
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
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(19.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MicroLabel("MODE", palette.primary.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    MicroLabel(
                        text = link,
                        color = palette.muted,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = name,
                    style = androidx.compose.ui.text.TextStyle(
                        brush = goldTextBrush(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // One live line: sequence while entering, last action after.
                if (buffer.isNotEmpty()) {
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
                } else {
                    Text(
                        text = eventLine(event),
                        color = when (event) {
                            is EngineEvent.FiredMacro -> palette.primary
                            is EngineEvent.FiredSingle -> palette.secondary
                            is EngineEvent.Unmapped -> palette.danger
                            else -> palette.secondary.copy(alpha = 0.75f)
                        },
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Diamond-pierced rule instead of a plain divider.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hairline(modifier = Modifier.width(24.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(palette.primary.copy(alpha = 0.8f))
                            .graphicsLayer { rotationZ = 45f },
                    )
                    Hairline(modifier = Modifier.width(24.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = compactSingleMap(single),
                    fontFamily = FontFamily.Monospace,
                    color = palette.secondary.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(13.dp))
            }
        }
    }
}

/** Small gold diamond for the free corners. */
@Composable
private fun OrnamentDiamond(modifier: Modifier = Modifier) {
    val palette = LocalHdPalette.current
    Box(
        modifier
            .size(5.dp)
            .graphicsLayer { rotationZ = 45f }
            .background(palette.primary.copy(alpha = 0.45f)),
    )
}

private fun eventLine(event: EngineEvent): String = when (event) {
    is EngineEvent.FiredMacro -> "激活「${event.macro.name}」"
    is EngineEvent.FiredSingle -> "${event.symbol.display} ${summarize(event.step)}"
    is EngineEvent.Unmapped -> "未映射 ${event.symbol.display}"
    is EngineEvent.Pending -> "序列中 ${event.buffer.joinToString(" ") { it.display }}"
    EngineEvent.SequenceTimeout -> "序列超时清空"
    EngineEvent.Idle -> "待命"
}

/** Compact transport code for the card header. */
private fun linkCode(s: TransportStatus): String = when {
    s.kind == TransportKind.LOGGING -> "SIM"
    s.label.contains("已连接") -> "HOST"
    s.label.contains("缺少权限") -> "AUTH"
    s.label.contains("不可用") || s.label.contains("失败") -> "DOWN"
    else -> "IDLE"
}

private fun compactSingleMap(single: Map<Symbol, Step>): String {
    fun q(sym: Symbol) = summarize(single[sym]).let { if (it == "—") "?" else it }
    return "↑${q(Symbol.UP)} ↓${q(Symbol.DOWN)} ←${q(Symbol.LEFT)} →${q(Symbol.RIGHT)}"
}
