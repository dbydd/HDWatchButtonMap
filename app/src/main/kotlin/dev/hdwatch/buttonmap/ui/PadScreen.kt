package dev.hdwatch.buttonmap.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.Step
import dev.hdwatch.buttonmap.engine.EngineEvent
import dev.hdwatch.buttonmap.hid.TransportKind
import dev.hdwatch.buttonmap.hid.TransportState
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
    val hint by app.engine.hintState.collectAsState()
    val latched by app.runner.latched.collectAsState()
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
        // One soft pulse per action — no strobing.
        hot = true
        delay(150)
        hot = false
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

    // Which keycap is under the finger. Held in a state holder that is read
    // only inside the draw lambda: a press repaints the dial without
    // recomposing the screen (worth ~10ms a tap on this SoC).
    val pressedDir = remember { mutableStateOf<ArcDir?>(null) }

    // Keys mode: the confirmation line clears itself 0.8s after the press.
    var showEvent by remember { mutableStateOf(false) }
    LaunchedEffect(event) {
        showEvent = event != EngineEvent.Idle
        if (showEvent) {
            delay(800)
            showEvent = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.edge),
    ) {
        // ---- dial face: the static geometry is one path per ink group and is
        // remembered per size; the per-frame work is a dozen draw calls, not
        // the ~200 the first version issued (48ms frames on the watch) ----
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val dial = remember(wPx, hPx, palette) { buildDial(wPx, hPx, palette) }
        Canvas(Modifier.fillMaxSize()) { drawDial(dial, palette, { flare }, pressedDir.value) }

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
                val slotStep = profile.ringSteps[sym.code]
                // A slot lights up only while it is pressed or its hold is on;
                // idle slots all read the same grey.
                val holdKey = (slotStep as? dev.hdwatch.buttonmap.config.Step.Hold)?.key
                    ?: (profile.single[sym] as? dev.hdwatch.buttonmap.config.Step.Hold)?.key
                RingSymbol(
                    symbol = sym,
                    lit = holdKey != null && latched.contains(holdKey),
                    angleDeg = center + off,
                    radiusDp = 83f,
                    modifier = Modifier.align(Alignment.Center),
                    onPress = { if (slotStep != null) app.runner.holdStep(slotStep) else feed(sym) },
                    onRelease = { slotStep?.let { app.runner.releaseHold(it) } },
                )
            }
        }

        // ---- four keycap touch pads (the caps themselves are painted) ----
        ArrowCap(
            symbol = Symbol.UP,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-83).dp)
                .size(width = 68.dp, height = 42.dp),
            onPressedChange = { pressedDir.value = if (it) ArcDir.UP else null },
        ) { feed(Symbol.UP) }
        ArrowCap(
            symbol = Symbol.DOWN,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 83.dp)
                .size(width = 68.dp, height = 42.dp),
            onPressedChange = { pressedDir.value = if (it) ArcDir.DOWN else null },
        ) { feed(Symbol.DOWN) }
        ArrowCap(
            symbol = Symbol.LEFT,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-83).dp)
                .size(width = 42.dp, height = 68.dp),
            onPressedChange = { pressedDir.value = if (it) ArcDir.LEFT else null },
        ) { feed(Symbol.LEFT) }
        ArrowCap(
            symbol = Symbol.RIGHT,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 83.dp)
                .size(width = 42.dp, height = 68.dp),
            onPressedChange = { pressedDir.value = if (it) ArcDir.RIGHT else null },
        ) { feed(Symbol.RIGHT) }

        // ---- hub plaque ----
        val link = linkCode(transportStatus)
        HubCard(
            profile = profile,
            buffer = buffer,
            hint = hint,
            showEvent = showEvent,
            event = event,
            link = link,
            flareProvider = { flare },
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
    lit: Boolean,
    angleDeg: Double,
    radiusDp: Float,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val palette = LocalHdPalette.current
    var pressed by remember { mutableStateOf(false) }
    val on = lit || pressed
    val dx = sin(Math.toRadians(angleDeg)).toFloat()
    val dy = -cos(Math.toRadians(angleDeg)).toFloat()
    val label = symbol.display ?: symbol.code
    Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = if (label.length > 1) 9.sp else 11.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) palette.primary.copy(alpha = 0.95f) else palette.muted.copy(alpha = 0.8f),
        maxLines = 1,
        modifier = modifier
            .offset(x = (dx * radiusDp).dp, y = (dy * radiusDp).dp)
            .graphicsLayer {
                rotationZ = if (angleDeg <= 180) (angleDeg + 90).toFloat() else (angleDeg - 90).toFloat()
            }
            // Fire on touch-down; hold slots lift their key on release.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress()
                    waitForUpOrCancellation()
                    pressed = false
                    onRelease()
                }
            }
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
    onPress: () -> Unit,
) {
    val palette = LocalHdPalette.current
    var held by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                // Down = action: no tap timeout, no up-wait. The host gets the
                // report while the finger is still on the glass.
                val down = awaitFirstDown(requireUnconsumed = false)
                HdApp.instance.ring.log(
                    "UI  touch->fire ${android.os.SystemClock.uptimeMillis() - down.uptimeMillis}ms",
                )
                held = true
                onPressedChange(true)
                onPress()
                waitForUpOrCancellation()
                held = false
                onPressedChange(false)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol.display,
            color = if (held) palette.onPrimary else palette.text,
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
    hint: List<Symbol>,
    showEvent: Boolean,
    event: EngineEvent,
    link: String,
    flareProvider: () -> Float,
    onCycle: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    // Read here on purpose: the medallion halo follows the flash, and this
    // small subtree recomposing beats the whole screen doing it.
    val flare = flareProvider()
    val outerShape = remember(modifier) { OctagonShape(7.dp) }
    val innerShape = remember(modifier) { OctagonShape(5.dp) }
    Box(modifier) {
        // Outer octagon hairline ring.
        Box(
            Modifier
                .fillMaxSize()
                .border(1.dp, palette.primary.copy(alpha = 0.28f + 0.15f * flare), outerShape),
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
                            androidx.compose.ui.graphics.lerp(palette.panelTop, palette.primary, 0.04f + 0.10f * flare),
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
                            palette.primary.copy(alpha = 0.32f + 0.20f * flare),
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
                if (profile.kind == ProfileKind.MACRO) {
                    // Macro mode: the typed prefix (touch only) on top, then
                    // whatever matches it. Nothing typed = nothing suggested.
                    if (buffer.isNotEmpty()) {
                        Text(
                            text = buffer.joinToString(" ") { it.display ?: it.code },
                            fontFamily = FontFamily.Monospace,
                            color = palette.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(3.dp))
                        val shown = if (hint.isEmpty()) {
                            emptyList()
                        } else {
                            profile.macros.filter {
                                it.enabled && it.sequence.size >= hint.size &&
                                    it.sequence.subList(0, hint.size) == hint
                            }
                        }
                        shown.take(3).forEach { m ->
                            Row(
                                modifier = Modifier.basicMarquee(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = m.name,
                                    fontSize = 6.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (m == shown.first()) palette.primary else palette.primary.copy(alpha = 0.7f),
                                    maxLines = 1,
                                )
                                Text(
                                    text = m.sequence.joinToString("") { it.display ?: it.code },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 6.sp,
                                    color = palette.secondary.copy(alpha = 0.75f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                } else {
                    // Keys mode: confirmation line, self-clearing after 0.8s.
                    if (event != EngineEvent.Idle && showEvent) {
                        val line = eventLine(event)
                        Text(
                            text = line,
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

/**
 * Compact transport code for the hub footer. [TransportStatus] exposes no state
 * enum and its label comes from the transport layer, so the state is read off
 * that label: the literals below are protocol constants matching
 * hid/BluetoothHidTransport.kt, not display copy — move them together with it.
 */
@Composable
private fun linkCode(s: TransportStatus): String = when (s.state) {
    TransportState.LOGGING -> stringResource(R.string.pad_link_sim)
    TransportState.CONNECTED -> stringResource(R.string.pad_link_host)
    TransportState.NEEDS_PERMISSION -> stringResource(R.string.pad_link_auth)
    TransportState.UNAVAILABLE,
    TransportState.NO_ADAPTER,
    TransportState.REGISTER_FAILED,
    -> stringResource(R.string.pad_link_down)
    else -> stringResource(R.string.pad_link_idle)
}

@Composable
private fun eventLine(event: EngineEvent): String = when (event) {
    is EngineEvent.FiredMacro -> stringResource(R.string.pad_event_fired_macro, event.macro.name)
    is EngineEvent.FiredSingle -> {
        val stepSummary = summarize(event.step)
        "${event.symbol.display} $stepSummary"
    }
    is EngineEvent.Unmapped -> stringResource(R.string.pad_event_unmapped, event.symbol.display)
    is EngineEvent.Pending -> {
        val typed = event.buffer.joinToString(" ") { it.display }
        stringResource(R.string.pad_event_pending, typed)
    }
    EngineEvent.SequenceTimeout -> stringResource(R.string.pad_event_seq_timeout)
    EngineEvent.Idle -> stringResource(R.string.pad_event_idle)
}

/** Static dial geometry: one path per ink group, built once per size. */
private class DialGeometry(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val dome: Brush,
    val majorRays: Path,
    val minorRays: Path,
    val ticks: Path,
    val dashes: Path,
    val fans: Map<ArcDir, Path>,
    val panelBrush: Brush,
    val rimBrush: Brush,
    val goldBrush: Brush,
) {
    val dashRingRadius = radius * 0.7345f
}

private fun buildDial(width: Float, height: Float, palette: HdPalette): DialGeometry {
    val cx = width / 2f
    val cy = height / 2f
    val r = minOf(width, height) / 2f
    // Dial bearing: 0° = up, clockwise, degrees in — radians inside.
    fun at(angleDeg: Double, radius: Float): Offset {
        val rad = Math.toRadians(angleDeg)
        return Offset(cx + (radius * sin(rad)).toFloat(), cy - (radius * cos(rad)).toFloat())
    }
    fun Path.segment(from: Offset, to: Offset) {
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
    }

    val majorRays = Path()
    val minorRays = Path()
    var i = 0
    while (i < 72) {
        val a = i * 5.0
        if (i % 3 == 0) majorRays.segment(at(a, r * 0.53f), at(a, r * 0.99f))
        else minorRays.segment(at(a, r * 0.56f), at(a, r * 0.94f))
        i++
    }
    val ticks = Path()
    i = 0
    while (i < 24) {
        if (i % 6 != 0) {
            val a = i * 15.0
            ticks.segment(at(a, r * 0.915f), at(a, r * 0.955f))
        }
        i++
    }
    val dashes = Path()
    i = 0
    while (i < 48) {
        val a = i * 7.5
        dashes.segment(at(a, r * 0.7345f), at(a + 4.2, r * 0.7345f))
        i++
    }
    // Keycap sectors: true annular fans at real radii (inner 62dp → outer
    // 104dp on a 113dp dial), drawn once per size.
    val rIn = r * 0.5487f
    val rOut = r * 0.9204f
    val half = 24f
    val fans = ArcDir.entries.associateWith { dir ->
        val start = dir.dialAngle - 90f - half
        Path().apply {
            arcTo(Rect(cx - rOut, cy - rOut, cx + rOut, cy + rOut), start, half * 2f, true)
            arcTo(Rect(cx - rIn, cy - rIn, cx + rIn, cy + rIn), start + half * 2f, -half * 2f, false)
            close()
        }
    }
    return DialGeometry(
        cx = cx,
        cy = cy,
        radius = r,
        dome = Brush.radialGradient(
            listOf(palette.surface, palette.background, palette.edge),
            Offset(cx, cy),
            r,
        ),
        majorRays = majorRays,
        minorRays = minorRays,
        ticks = ticks,
        dashes = dashes,
        fans = fans,
        panelBrush = Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom)),
        rimBrush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.26f), palette.primary.copy(alpha = 0.38f)),
        ),
        goldBrush = Brush.linearGradient(
            listOf(
                androidx.compose.ui.graphics.lerp(palette.primary, Color.White, 0.35f),
                palette.primary,
                palette.goldDeep,
            ),
        ),
    )
}

/** Per-frame dial paint: a dozen draw calls, however dense the face is. */
private fun DrawScope.drawDial(
    g: DialGeometry,
    palette: HdPalette,
    flareProvider: () -> Float,
    pressed: ArcDir?,
) {
    val flare = flareProvider()
    drawCircle(g.dome, g.radius, Offset(g.cx, g.cy))
    drawPath(g.majorRays, palette.primary.copy(alpha = 0.17f), style = Stroke(1.4f))
    drawPath(g.minorRays, palette.primary.copy(alpha = 0.07f), style = Stroke(1f))
    drawCircle(
        color = palette.primary.copy(alpha = 0.24f + 0.12f * flare),
        radius = g.radius * 0.965f,
        center = Offset(g.cx, g.cy),
        style = Stroke(1.5f),
    )
    drawCircle(
        color = palette.primary.copy(alpha = 0.10f),
        radius = g.radius * 0.885f,
        center = Offset(g.cx, g.cy),
        style = Stroke(1f),
    )
    drawPath(g.ticks, palette.primary.copy(alpha = 0.60f), style = Stroke(2f))
    drawPath(g.dashes, palette.primary.copy(alpha = 0.38f), style = Stroke(1.6f))
    if (flare > 0.01f) {
        // Ignition: the dashed ring turns solid and thickens.
        drawCircle(
            color = palette.primary.copy(alpha = 0.30f + 0.55f * flare),
            radius = g.dashRingRadius,
            center = Offset(g.cx, g.cy),
            style = Stroke(1.6f + (2.dp.toPx() - 1.6f) * flare),
        )
    }
    ArcDir.entries.forEach { dir ->
        val fan = g.fans.getValue(dir)
        if (pressed == dir) drawPath(fan, g.goldBrush) else drawPath(fan, g.panelBrush, alpha = 0.97f)
        drawPath(fan, g.rimBrush, style = Stroke(2f))
    }
}

