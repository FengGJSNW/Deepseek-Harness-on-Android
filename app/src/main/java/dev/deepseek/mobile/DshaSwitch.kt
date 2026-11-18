package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** A quiet continuous-corner switch with a generous independent hit target. */
@Composable
internal fun DshaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val haptics = rememberUiHaptics()
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val held by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val lift by
        animateFloatAsState(if (held && enabled) 1f else 0f, tween(110), label = "switch-press")
    val position by
        animateFloatAsState(if (checked) 1f else 0f, spring(.88f, 700f), label = "dsha-switch")
    Canvas(
        modifier
            .size(width = 56.dp, height = 48.dp)
            .then(
                if (onCheckedChange == null) Modifier
                else
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = interactions,
                        indication = null,
                        onValueChange = { next ->
                            if (next != checked) {
                                haptics.toggle(next)
                                onCheckedChange(next)
                            }
                        },
                    ),
            ),
    ) {
        val trackSize = Size(50.dp.toPx(), 30.dp.toPx())
        val origin =
            Offset((size.width - trackSize.width) / 2, (size.height - trackSize.height) / 2)
        val track = ContinuousShape(15.dp).createOutline(trackSize, layoutDirection, this)
        val alpha = if (enabled) 1f else .4f
        translate(origin.x, origin.y) {
            drawOutline(track, lerp(Color(0xFFE4E3E9), WhaleBlue, position).copy(alpha = alpha))
            drawOutline(
                track,
                (if (focused) WhaleBlue else Color.Transparent).copy(
                    alpha = if (focused) alpha else 0f,
                ),
                style = Stroke((if (focused) 1.8f else .8f).dp.toPx()),
            )
            val thumbWidth = 24f + 4f * lift
            val along = if (layoutDirection == LayoutDirection.Rtl) 1f - position else position
            val thumb =
                ContinuousShape(12.dp)
                    .createOutline(Size(thumbWidth.dp.toPx(), 24.dp.toPx()), layoutDirection, this)
            translate((3 + (44 - thumbWidth) * along).dp.toPx(), 3.dp.toPx()) {
                translate(0f, .7.dp.toPx()) {
                    drawOutline(thumb, Color.Black.copy(alpha = (.08f + .05f * lift) * alpha))
                }
                drawOutline(
                    thumb,
                    lerp(Color(0xFF858998), Color.White, (position + .45f * lift).coerceIn(0f, 1f))
                        .copy(alpha = alpha),
                )
            }
        }
    }
}
