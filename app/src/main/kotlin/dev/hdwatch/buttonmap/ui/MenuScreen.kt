package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.HdApp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ProfileKind
import dev.hdwatch.buttonmap.config.ProfilePresets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared screen chrome adapted for both round (40mm–47mm+) and square watches,
 * with rotary crown/bezel scrolling, edge vignettes, a subtle curved scroll
 * progress arc, and a tactile inset back button.
 */
@Composable
fun ScreenScaffold(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = LocalHdPalette.current
    val nav = LocalAppNav.current
    val isRound = LocalConfiguration.current.isScreenRound
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(palette.surface, palette.background, palette.edge),
                ),
            ),
    ) {
        val horizPad: Dp = if (isRound) (maxWidth * 0.08f).coerceIn(10.dp, 20.dp) else 8.dp
        val topBand: Dp = if (isRound) (maxHeight * 0.185f).coerceIn(34.dp, 46.dp) else 32.dp
        val bottomPad: Dp = if (isRound) (maxHeight * 0.21f).coerceIn(34.dp, 52.dp) else 16.dp
        val backStart: Dp = if (isRound) (maxWidth * 0.21f).coerceIn(34.dp, 52.dp) else 8.dp
        val backTop: Dp = if (isRound) (maxHeight * 0.075f).coerceIn(12.dp, 20.dp) else 5.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { ev ->
                    scope.launch { scrollState.scrollBy(ev.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .padding(start = horizPad, end = horizPad, top = topBand, bottom = 0.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicroLabel(
                text = title,
                color = palette.primary.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            content()
            Spacer(Modifier.height(bottomPad))
        }

        ScrollEdgeVignettes(
            edgeColor = palette.edge,
            topHeight = topBand + 14.dp,
            bottomHeight = if (isRound) 28.dp else 14.dp,
        )

        if (scrollState.maxValue > 0) {
            val total = (scrollState.maxValue + scrollState.viewportSize).coerceAtLeast(1)
            ScrollRimIndicator(
                isRound = isRound,
                progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f),
                thumbFraction = (scrollState.viewportSize.toFloat() / total).coerceIn(0.18f, 0.72f),
            )
        }

        BackAffordance(
            onBack = { nav.pop() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = backStart, top = backTop),
        )
    }
}

/**
 * LazyColumn counterpart of [ScreenScaffold] for long lists (e.g. the 113-macro
 * HD2 pack) so Wear OS watches scroll smoothly without eager composition.
 */
@Composable
fun LazyScreenScaffold(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    val palette = LocalHdPalette.current
    val nav = LocalAppNav.current
    val isRound = LocalConfiguration.current.isScreenRound
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val scrollMetrics by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            if (total <= visible || visible == 0) {
                null
            } else {
                val progress = (listState.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1))
                    .coerceIn(0f, 1f)
                val thumb = (visible.toFloat() / total).coerceIn(0.16f, 0.72f)
                progress to thumb
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(palette.surface, palette.background, palette.edge),
                ),
            ),
    ) {
        val horizPad: Dp = if (isRound) (maxWidth * 0.08f).coerceIn(10.dp, 20.dp) else 8.dp
        val topBand: Dp = if (isRound) (maxHeight * 0.185f).coerceIn(34.dp, 46.dp) else 32.dp
        val bottomPad: Dp = if (isRound) (maxHeight * 0.22f).coerceIn(36.dp, 54.dp) else 18.dp
        val backStart: Dp = if (isRound) (maxWidth * 0.21f).coerceIn(34.dp, 52.dp) else 8.dp
        val backTop: Dp = if (isRound) (maxHeight * 0.075f).coerceIn(12.dp, 20.dp) else 5.dp

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { ev ->
                    scope.launch { listState.scrollBy(ev.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            contentPadding = PaddingValues(
                start = horizPad,
                end = horizPad,
                top = topBand,
                bottom = bottomPad,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "__title__") {
                MicroLabel(
                    text = title,
                    color = palette.primary.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
            }
            content()
        }

        ScrollEdgeVignettes(
            edgeColor = palette.edge,
            topHeight = topBand + 14.dp,
            bottomHeight = if (isRound) 28.dp else 14.dp,
        )

        scrollMetrics?.let { (progress, thumb) ->
            ScrollRimIndicator(
                isRound = isRound,
                progress = progress,
                thumbFraction = thumb,
            )
        }

        BackAffordance(
            onBack = { nav.pop() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = backStart, top = backTop),
        )
    }
}

/** Soft top/bottom fade masks so scrolling cards dissolve smoothly into the rim. */
@Composable
private fun ScrollEdgeVignettes(
    edgeColor: Color,
    topHeight: Dp,
    bottomHeight: Dp,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topHeight)
                .background(
                    Brush.verticalGradient(
                        0.0f to edgeColor.copy(alpha = 0.88f),
                        0.62f to edgeColor.copy(alpha = 0.45f),
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomHeight)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        1.0f to edgeColor.copy(alpha = 0.82f),
                    ),
                ),
        )
    }
}

/** Delicate curved scroll indicator along the right rim (straight on square watches). */
@Composable
private fun ScrollRimIndicator(
    isRound: Boolean,
    progress: Float,
    thumbFraction: Float,
) {
    val palette = LocalHdPalette.current
    Canvas(Modifier.fillMaxSize()) {
        val strokePx = 2.dp.toPx()
        val insetPx = 4.5.dp.toPx()
        if (isRound) {
            val r = minOf(size.width, size.height) / 2f - insetPx
            val topLeft = Offset(size.width / 2f - r, size.height / 2f - r)
            val arcSize = Size(r * 2f, r * 2f)
            val totalSweep = 52f
            val startAngle = -totalSweep / 2f
            drawArc(
                color = palette.primary.copy(alpha = 0.14f),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            val thumbSweep = totalSweep * thumbFraction
            val thumbStart = startAngle + (totalSweep - thumbSweep) * progress
            drawArc(
                color = palette.primary.copy(alpha = 0.72f),
                startAngle = thumbStart,
                sweepAngle = thumbSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        } else {
            val x = size.width - insetPx
            val trackTop = size.height * 0.26f
            val trackLen = size.height * 0.48f
            drawLine(
                color = palette.primary.copy(alpha = 0.14f),
                start = Offset(x, trackTop),
                end = Offset(x, trackTop + trackLen),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            val thumbLen = trackLen * thumbFraction
            val thumbTop = trackTop + (trackLen - thumbLen) * progress
            drawLine(
                color = palette.primary.copy(alpha = 0.72f),
                start = Offset(x, thumbTop),
                end = Offset(x, thumbTop + thumbLen),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun BackAffordance(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    var pressed by remember { mutableStateOf(false) }
    // Edge-swipe back exits the whole app on some watches, so every config
    // screen carries a reliable top-inset back affordance.
    Box(
        modifier = modifier
            .size(34.dp)
            .background(
                color = if (pressed) palette.primary.copy(alpha = 0.24f) else Color.Transparent,
                shape = CircleShape,
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptics.tick()
                    onBack()
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "‹",
            color = if (pressed) palette.primary else palette.secondary.copy(alpha = 0.85f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun MenuRow(
    label: String,
    hint: String = "",
    accent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = Modifier
            .widthIn(min = 124.dp, max = 176.dp)
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        if (accent) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 7.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.primary, palette.primary.copy(alpha = 0.15f)),
                        ),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        Column(Modifier.padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                label,
                color = if (pressed) palette.onPrimary else palette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.7f) else palette.muted,
                    fontSize = 9.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
fun MenuScreen() {
    val app = HdApp.instance
    val nav = LocalAppNav.current
    val haptics = LocalHaptics.current
    val config by app.configRepo.config.collectAsState()
    val transport = remember(config.settings.forceLoggingTransport) {
        app.transports.forSettings(config.settings)
    }
    val status by transport.status.collectAsState()

    var showProfiles by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var pendingDeleteProfile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingDeleteProfile) {
        if (pendingDeleteProfile != null) {
            delay(3500)
            pendingDeleteProfile = null
        }
    }

    ScreenScaffold(title = stringResource(R.string.scr_menu_title)) {
        MenuRow(
            label = stringResource(R.string.scr_menu_mode, config.activeProfile.name),
            hint = if (showProfiles) {
                stringResource(R.string.scr_menu_collapse)
            } else {
                stringResource(R.string.scr_menu_mode_hint, config.profiles.size)
            },
            accent = true,
        ) {
            showProfiles = !showProfiles
            if (!showProfiles) {
                showPresets = false
                pendingDeleteProfile = null
            }
        }
        if (showProfiles) {
            config.profiles.forEach { profile ->
                val isActive = profile.id == config.activeProfileId
                val isConfirmingDelete = pendingDeleteProfile == profile.id
                val kindLabel = stringResource(
                    if (profile.kind == ProfileKind.KEYS) R.string.scr_menu_kind_keys else R.string.scr_menu_kind_macro,
                )
                MenuRow(
                    label = when {
                        isConfirmingDelete -> stringResource(R.string.scr_menu_confirm_delete_profile, profile.name)
                        isActive -> "✓ ${profile.name}"
                        else -> profile.name
                    },
                    hint = if (config.profiles.size > 1 && !isConfirmingDelete) {
                        stringResource(R.string.scr_menu_profile_hint, kindLabel)
                    } else {
                        kindLabel
                    },
                    accent = isActive,
                    onLongClick = if (config.profiles.size > 1) {
                        {
                            haptics.tick()
                            pendingDeleteProfile = profile.id
                        }
                    } else {
                        null
                    },
                ) {
                    if (isConfirmingDelete) {
                        app.configRepo.deleteProfile(profile.id)
                        pendingDeleteProfile = null
                    } else {
                        pendingDeleteProfile = null
                        app.configRepo.setActiveProfile(profile.id)
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 3.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionButton(
                    text = stringResource(
                        if (showPresets) R.string.scr_menu_presets_hide else R.string.scr_menu_presets_add,
                    ),
                    tone = HdTone.Accent,
                    onClick = { showPresets = !showPresets },
                )
            }
            if (showPresets) {
                ProfilePresets.all.forEach { preset ->
                    val already = config.profiles.any { it.id == preset.profileId }
                    MenuRow(
                        label = "+ " + stringResource(preset.nameRes),
                        hint = if (already) {
                            stringResource(R.string.scr_menu_preset_refresh, stringResource(preset.hintRes))
                        } else {
                            stringResource(preset.hintRes)
                        },
                    ) {
                        haptics.tick()
                        app.configRepo.installPreset(preset.id)
                        showPresets = false
                    }
                }
            }
        }
        MenuRow(
            stringResource(R.string.scr_menu_single),
            stringResource(R.string.scr_menu_single_hint),
        ) { nav.push(Route.MappingList) }
        MenuRow(
            stringResource(R.string.scr_menu_macros),
            stringResource(
                R.string.scr_menu_macro_count,
                config.activeProfile.macros.count { it.enabled },
                config.activeProfile.macros.size,
            ),
        ) { nav.push(Route.MacroList) }
        MenuRow(stringResource(R.string.scr_menu_bluetooth), status.label + " · " + status.detail) { nav.push(Route.Hid) }
        MenuRow(stringResource(R.string.scr_menu_settings)) { nav.push(Route.Settings) }
        MenuRow(stringResource(R.string.scr_menu_log)) { nav.push(Route.Log) }
    }
}
