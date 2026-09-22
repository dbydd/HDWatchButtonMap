package dev.hdwatch.buttonmap.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * The pad: a Helldivers console face. Four glass direction cells frame one
 * haloed focus card; the card owns the profile HUD and the mode switch.
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

    // Ignition: the focus card halo flares twice when an action fires.
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 22.dp, end = 22.dp, top = 15.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Spacer(Modifier.weight(2.7f))
                PadCell(
                    symbol = Symbol.UP,
                    action = profile.single[Symbol.UP],
                    modifier = Modifier.weight(3.6f).fillMaxHeight(),
                ) {
                    haptics.press()
                    app.engine.feed(Symbol.UP)
                }
                Spacer(Modifier.weight(2.7f))
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(2.5f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PadCell(
                    symbol = Symbol.LEFT,
                    action = profile.single[Symbol.LEFT],
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    haptics.press()
                    app.engine.feed(Symbol.LEFT)
                }
                FocusCard(
                    name = profile.name,
                    buffer = buffer,
                    event = event,
                    single = profile.single,
                    flare = flare,
                    modifier = Modifier.weight(3.6f).fillMaxHeight(),
                    onCycle = {
                        haptics.tick()
                        app.configRepo.cycleActiveProfile()
                    },
                    onMenu = { nav.push(Route.Menu) },
                )
                PadCell(
                    symbol = Symbol.RIGHT,
                    action = profile.single[Symbol.RIGHT],
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    haptics.press()
                    app.engine.feed(Symbol.RIGHT)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Spacer(Modifier.weight(2.7f))
                PadCell(
                    symbol = Symbol.DOWN,
                    action = profile.single[Symbol.DOWN],
                    modifier = Modifier.weight(3.6f).fillMaxHeight(),
                ) {
                    haptics.press()
                    app.engine.feed(Symbol.DOWN)
                }
                Spacer(Modifier.weight(2.7f))
            }
        }
        // Footer: link readout between two fading rules.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Hairline(modifier = Modifier.width(22.dp))
            MicroLabel(
                text = summarizeStatus(transportStatus),
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Hairline(modifier = Modifier.width(22.dp))
        }
    }
}

/** Direction cell: glass panel, glyph over its bound key, gold flood on press. */
@Composable
private fun PadCell(
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
        HdPanel(
            pressed = pressed,
            interactionSource = interaction,
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = symbol.display,
                    color = if (pressed) palette.onPrimary else palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = key,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.75f) else palette.secondary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The haloed focus card: profile identity, live sequence, mode switch. */
@Composable
private fun FocusCard(
    name: String,
    buffer: List<Symbol>,
    event: EngineEvent,
    single: Map<Symbol, Step>,
    flare: Float,
    onCycle: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    val shape = RoundedCornerShape(8.dp)

    Box(modifier) {
        // Ignition halo behind the panel.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.primary.copy(alpha = 0.10f + 0.30f * flare),
                            palette.primary.copy(alpha = 0.03f + 0.12f * flare),
                        ),
                    ),
                    shape,
                )
                .padding(2.dp),
        ) {
            HdPanel(
                shape = shape,
                onClick = onCycle,
                onLongClick = onMenu,
                modifier = Modifier.fillMaxSize(),
            ) {
                CornerBrackets(
                    color = palette.primary.copy(alpha = 0.85f + 0.15f * flare),
                    size = 10.dp,
                    stroke = 1.5.dp,
                    inset = 2.dp,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MicroLabel(
                        text = "MODE",
                        color = palette.primary.copy(alpha = 0.55f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = name,
                        style = androidx.compose.ui.text.TextStyle(
                            brush = goldTextBrush(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    // One live line: sequence while entering, last action after.
                    if (buffer.isNotEmpty()) {
                        Text(
                            text = buffer.joinToString(" ") { it.display },
                            fontFamily = FontFamily.Monospace,
                            color = palette.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            maxLines = 2,
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
                    Hairline()
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = compactSingleMap(single),
                        fontFamily = FontFamily.Monospace,
                        color = palette.secondary.copy(alpha = 0.6f),
                        fontSize = 8.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun eventLine(event: EngineEvent): String = when (event) {
    is EngineEvent.FiredMacro -> "激活「${event.macro.name}」"
    is EngineEvent.FiredSingle -> "${event.symbol.display} ${summarize(event.step)}"
    is EngineEvent.Unmapped -> "未映射 ${event.symbol.display}"
    is EngineEvent.Pending -> "序列中 ${event.buffer.joinToString(" ") { it.display }}"
    EngineEvent.SequenceTimeout -> "序列超时清空"
    EngineEvent.Idle -> "待命"
}

private fun summarizeStatus(s: TransportStatus): String = when {
    s.kind == TransportKind.LOGGING -> "LINK · SIM"
    s.label.contains("已连接") -> "LINK · ${s.hostName ?: "HOST"}"
    s.label.contains("缺少权限") -> "LINK · AUTH"
    s.label.contains("不可用") || s.label.contains("失败") -> "LINK · DOWN"
    else -> "LINK · IDLE"
}

private fun compactSingleMap(single: Map<Symbol, Step>): String {
    fun q(sym: Symbol) = summarize(single[sym]).let { if (it == "—") "?" else it }
    return "↑${q(Symbol.UP)}  ↓${q(Symbol.DOWN)}  ←${q(Symbol.LEFT)}  →${q(Symbol.RIGHT)}"
}
