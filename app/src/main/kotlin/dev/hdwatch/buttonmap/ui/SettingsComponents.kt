package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hdwatch.buttonmap.R
import dev.hdwatch.buttonmap.config.ThemePreset
import dev.hdwatch.buttonmap.input.CapStatus
import dev.hdwatch.buttonmap.input.Symbol

/** Uniform card width constraint across all watch sizes (40mm–47mm round & square). */
internal fun Modifier.settingsCardWidth(): Modifier =
    this.widthIn(min = 124.dp, max = 176.dp).fillMaxWidth()

/** One tile inside the Settings Hub quick-toggle dock. */
internal data class QuickToggleItem(
    val label: String,
    val checked: Boolean,
    val onToggle: () -> Unit,
)

/**
 * 3-tile quick-control dock pinned at the top of the Settings Hub so occasional
 * users can flip Vibration / Keep Awake / Keep-Alive in one tap without scrolling.
 */
@Composable
internal fun QuickToggleDock(
    items: List<QuickToggleItem>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    Row(
        modifier = modifier
            .settingsCardWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items.forEach { item ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            HdPanel(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(8.dp),
                pressed = pressed,
                interactionSource = interaction,
                onClick = {
                    haptics.tick()
                    item.onToggle()
                },
                contentAlignment = Alignment.Center,
            ) {
                if (item.checked && !pressed) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, palette.primary, Color.Transparent),
                                ),
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 3.dp),
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(
                                when {
                                    pressed -> palette.onPrimary
                                    item.checked -> palette.primary
                                    else -> palette.muted.copy(alpha = 0.45f)
                                },
                                CircleShape,
                            ),
                    )
                    Text(
                        text = item.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
                        fontWeight = if (item.checked) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                        color = when {
                            pressed -> palette.onPrimary
                            item.checked -> palette.primary
                            else -> palette.muted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Hub category row: recessed geometric glyph socket, title, live value summary,
 * and right chevron so the user sees current settings before even drilling in.
 */
@Composable
internal fun SettingsCategoryRow(
    glyph: String,
    title: String,
    summary: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = Modifier
            .settingsCardWidth()
            .padding(vertical = 2.5.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = {
            haptics.tick()
            onClick()
        },
    ) {
        if (accent && !pressed) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (pressed) {
                            Brush.verticalGradient(listOf(palette.goldDeep, palette.goldDeep))
                        } else {
                            Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom))
                        },
                        RoundedCornerShape(5.dp),
                    )
                    .border(1.dp, palette.hairline, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pressed) palette.text else palette.primary,
                    maxLines = 1,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp),
            ) {
                Text(
                    text = title,
                    color = if (pressed) palette.onPrimary else palette.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    fontFamily = FontFamily.Monospace,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.75f) else palette.muted,
                    fontSize = 8.5.sp,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "›",
                color = if (pressed) palette.onPrimary else palette.muted.copy(alpha = 0.75f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Watch-ergonomic numeric stepper:
 *  - Label (left) + live formatted value (right) at the top
 *  - Precision progress bar showing position within [min, max]
 *  - Fixed-width − / + pads pinned to the outer corners so buttons NEVER shift
 *    under the finger when the digit count changes.
 */
@Composable
internal fun StepperCard(
    label: String,
    value: Long,
    displayValue: String,
    min: Long,
    max: Long,
    step: Long,
    hint: String = "",
    onChange: (Long) -> Unit,
) {
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    val fraction = if (max > min) {
        ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    HdPanel(
        modifier = Modifier
            .settingsCardWidth()
            .padding(vertical = 2.5.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = palette.text,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = displayValue,
                    fontFamily = FontFamily.Monospace,
                    color = palette.primary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    color = palette.muted,
                    fontSize = 8.5.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Horological track bar
            Box(
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(1.5.dp))
                    .border(0.5.dp, palette.hairline, RoundedCornerShape(1.5.dp)),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(listOf(palette.goldDeep, palette.primary)),
                                RoundedCornerShape(1.5.dp),
                            ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StepperButton(
                    symbol = "−",
                    enabled = value > min,
                    modifier = Modifier.weight(1f),
                ) {
                    haptics.tick()
                    onChange((value - step).coerceIn(min, max))
                }
                StepperButton(
                    symbol = "+",
                    enabled = value < max,
                    modifier = Modifier.weight(1f),
                ) {
                    haptics.tick()
                    onChange((value + step).coerceIn(min, max))
                }
            }
        }
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(6.dp),
        pressed = enabled && pressed,
        interactionSource = interaction,
        onClick = if (enabled) onClick else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> palette.muted.copy(alpha = 0.4f)
                pressed -> palette.onPrimary
                else -> palette.secondary
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Full-width visual palette card with 3-bar swatch preview, zero horizontal cramping. */
@Composable
internal fun PaletteCard(
    preset: ThemePreset,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val preview = remember(preset) { paletteFor(preset) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    HdPanel(
        modifier = Modifier
            .settingsCardWidth()
            .padding(vertical = 2.5.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = pressed,
        interactionSource = interaction,
        onClick = onSelect,
    ) {
        if (selected && !pressed) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 7.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(listOf(palette.primary, palette.primary.copy(alpha = 0.15f))),
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 3-tone watch face swatch pill
            Row(
                modifier = Modifier
                    .size(width = 24.dp, height = 22.dp)
                    .border(1.dp, palette.hairline, RoundedCornerShape(5.dp))
                    .background(preview.edge, RoundedCornerShape(5.dp))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().background(preview.surface, RoundedCornerShape(2.dp)))
                Box(Modifier.weight(1f).fillMaxHeight().background(preview.panelTop, RoundedCornerShape(2.dp)))
                Box(Modifier.weight(1f).fillMaxHeight().background(preview.primary, RoundedCornerShape(2.dp)))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = if (selected) "✓ $title" else title,
                    color = when {
                        pressed -> palette.onPrimary
                        selected -> palette.primary
                        else -> palette.text
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = if (pressed) palette.onPrimary.copy(alpha = 0.72f) else palette.muted,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Aligned 2-button action row matching card width so buttons never clip circular rims. */
@Composable
internal fun ActionGridRow(
    leftText: String,
    onLeft: () -> Unit,
    rightText: String,
    onRight: () -> Unit,
    leftTone: HdTone = HdTone.Neutral,
    rightTone: HdTone = HdTone.Neutral,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .settingsCardWidth()
            .padding(vertical = 2.5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ActionButton(
            text = leftText,
            onClick = onLeft,
            tone = leftTone,
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            text = rightText,
            onClick = onRight,
            tone = rightTone,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Gesture sensor row: status pip + sensor label + right-aligned slot badge (`G1`..`G4` or `—`). */
@Composable
internal fun GestureCapabilityRow(
    label: String,
    status: CapStatus,
    bound: Symbol?,
    onClick: () -> Unit,
) {
    val palette = LocalHdPalette.current
    val haptics = LocalHaptics.current
    val bindable = status == CapStatus.AVAILABLE
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val capColor = when (status) {
        CapStatus.AVAILABLE -> palette.primary
        CapStatus.GATED -> palette.muted
        CapStatus.NEEDS_PERMISSION -> palette.danger
        CapStatus.BROKEN -> palette.muted.copy(alpha = 0.5f)
    }
    val statusText = when (status) {
        CapStatus.AVAILABLE -> stringResource(R.string.set_gesture_available)
        CapStatus.GATED -> stringResource(R.string.set_gesture_gated)
        CapStatus.NEEDS_PERMISSION -> stringResource(R.string.set_gesture_needs_permission)
        CapStatus.BROKEN -> stringResource(R.string.set_gesture_broken)
    }
    HdPanel(
        modifier = Modifier
            .settingsCardWidth()
            .padding(vertical = 2.5.dp),
        shape = RoundedCornerShape(8.dp),
        pressed = bindable && pressed,
        interactionSource = interaction,
        onClick = if (bindable) {
            {
                haptics.tick()
                onClick()
            }
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(capColor, RoundedCornerShape(1.5.dp))
                    .border(1.dp, palette.hairline, RoundedCornerShape(1.5.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 7.dp, end = 6.dp),
            ) {
                Text(
                    text = label,
                    color = when {
                        bindable && pressed -> palette.onPrimary
                        status == CapStatus.BROKEN -> palette.muted
                        else -> palette.text
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (status == CapStatus.BROKEN) TextDecoration.LineThrough else null,
                )
                Text(
                    text = statusText,
                    color = if (bindable && pressed) palette.onPrimary.copy(alpha = 0.72f) else palette.muted,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Slot pill badge
            Box(
                modifier = Modifier
                    .background(
                        color = when {
                            bindable && pressed -> palette.goldDeep
                            bound != null -> palette.primary.copy(alpha = 0.16f)
                            else -> Color.Black.copy(alpha = 0.28f)
                        },
                        shape = RoundedCornerShape(5.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (bound != null) palette.primary.copy(alpha = 0.55f) else palette.hairline,
                        shape = RoundedCornerShape(5.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = bound?.code ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    fontWeight = if (bound != null) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        bindable && pressed -> palette.text
                        bound != null -> palette.primary
                        else -> palette.muted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
