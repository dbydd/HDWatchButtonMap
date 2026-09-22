package dev.hdwatch.buttonmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared HUD primitives: the glass-panel surface language every screen builds
 * on. Panels are top-lit gradients with a bright hairline rim; the focal panel
 * gets a gold halo and corner brackets, like the Helldivers console screens.
 */

/**
 * Glass console panel. The caller's [modifier] sizes the box; [pressed]
 * floods it gold the way an HD2 menu button lights up under the cursor.
 */
@Composable
fun HdPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(7.dp),
    pressed: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalHdPalette.current
    val fill = if (pressed) {
        Brush.verticalGradient(
            listOf(
                androidx.compose.ui.graphics.lerp(palette.primary, Color.White, 0.35f),
                palette.primary,
                palette.goldDeep,
            ),
        )
    } else {
        Brush.verticalGradient(listOf(palette.panelTop, palette.panelBottom))
    }
    val rim = if (pressed) {
        Brush.verticalGradient(listOf(palette.goldDeep, palette.goldDeep))
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.20f),
                palette.hairline,
                Color.White.copy(alpha = 0.07f),
            ),
        )
    }
    var boxModifier = modifier.background(fill, shape).border(1.dp, rim, shape)
    if (onClick != null) {
        boxModifier = boxModifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    Box(modifier = boxModifier, contentAlignment = contentAlignment, content = content)
}

/** Four L brackets framing the panel corners — the HUD focus marker. */
@Composable
fun CornerBrackets(
    color: Color,
    size: Dp = 9.dp,
    stroke: Dp = 1.2.dp,
    inset: Dp = 3.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        listOf(
            Alignment.TopStart, Alignment.TopEnd,
            Alignment.BottomStart, Alignment.BottomEnd,
        ).forEach { a ->
            Box(Modifier.align(a).padding(inset)) {
                Box(Modifier.size(size, stroke).background(color).align(a))
                Box(Modifier.size(stroke, size).background(color).align(a))
            }
        }
    }
}

/** Tiny letter-spaced monospace label — the console's structural voice. */
@Composable
fun MicroLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Fading 1px structural rule. */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val palette = LocalHdPalette.current
    val brush = if (vertical) {
        Brush.verticalGradient(listOf(Color.Transparent, palette.hairline, Color.Transparent))
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, palette.hairline, Color.Transparent))
    }
    Box(
        modifier = if (vertical) {
            modifier.width(1.dp).background(brush)
        } else {
            modifier.fillMaxWidth().height(1.dp).background(brush)
        },
    )
}

/** Metallic gold text fill for focal titles. */
@Composable
fun goldTextBrush(): Brush {
    val palette = LocalHdPalette.current
    return Brush.verticalGradient(
        listOf(
            androidx.compose.ui.graphics.lerp(palette.primary, Color.White, 0.45f),
            palette.primary,
            androidx.compose.ui.graphics.lerp(palette.primary, palette.goldDeep, 0.55f),
        ),
    )
}
