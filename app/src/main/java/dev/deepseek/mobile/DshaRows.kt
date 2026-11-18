package dev.deepseek.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared press treatment; clickable/selectable still own scroll cancellation and keyboard input.
 */
@Composable
internal fun Modifier.dshaPressFeedback(
    interactions: MutableInteractionSource,
    fill: Color = Color.Transparent,
    shape: Shape = ContinuousShape(14.dp),
    pressScale: Float = 1f,
): Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val scale =
        animateFloatAsState(
            if (pressed) pressScale else 1f,
            spring(.78f, 620f),
            label = "row-press",
        )
    val color by
        animateColorAsState(
            when {
                pressed -> WhaleBlue.copy(alpha = .09f).compositeOver(fill)
                focused || hovered -> WhaleBlue.copy(alpha = .055f).compositeOver(fill)
                else -> fill
            },
            tween(130),
            label = "row-tint",
        )
    return graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clip(shape)
        .background(color)
        .then(if (focused) Modifier.border(1.dp, WhaleBlue, shape) else Modifier)
}

/** One text column and fixed icon/trailing slots: selection never shifts the text. */
@Composable
internal fun DshaInteractiveRow(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    tint: Color = WhaleBlue,
    fill: Color = Color.Transparent,
    trailingIcon: ImageVector = AppIcons.ChevronRight,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val haptics = rememberUiHaptics()
    val click = {
        if (selected != true) haptics.perform(UiHaptic.Selection)
        onClick()
    }
    val input =
        if (selected == null)
            Modifier.clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = click,
            )
        else
            Modifier.selectable(
                selected = selected,
                interactionSource = interactions,
                indication = null,
                role = Role.RadioButton,
                onClick = click,
            )
    Row(
        modifier
            .groupRow(52.dp)
            .fillMaxWidth()
            .dshaPressFeedback(interactions, fill)
            .then(input)
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(21.dp), tint = tint)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
            Text(
                title,
                style = PrismTypography.body,
                color = WhaleInk,
            )
            if (detail.isNotEmpty())
                Text(
                    detail,
                    Modifier.padding(top = 3.dp),
                    style = PrismTypography.footnote,
                    color = WhaleMuted,
                )
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected == true) Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = tint)
            else if (selected == null)
                Icon(trailingIcon, null, Modifier.size(15.dp), tint = WhaleMuted)
        }
    }
}

@Composable
internal fun DshaGlassGroup(content: @Composable RowScope.() -> Unit) {
    val motion = rememberGlassPress()
    CompositionLocalProvider(LocalGlassPress provides motion) {
        Box(Modifier.glassPress(motion)) {
            LiquidGlass(
                Modifier.matchParentSize()
                    .padding(4.dp)
                    .shadow(
                        2.dp,
                        CircleShape,
                        clip = false,
                        ambientColor = Color(0x180E1729),
                        spotColor = Color(0x200E1729),
                    )
                    .clip(CircleShape),
                corner = 22f,
                tint = Color(0x93F4F4F4),
                finish = GlassFinish.Pill,
                shape = CircleShape,
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
internal fun DshaIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    glass: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val haptics = rememberUiHaptics()
    val groupMotion = LocalGlassPress.current
    val motion = if (glass && groupMotion == null) rememberGlassPress() else groupMotion
    val focused by interactions.collectIsFocusedAsState()
    val feedback =
        if (motion != null) {
            (if (glass && groupMotion == null) Modifier.glassPress(motion, enabled) else Modifier)
                .then(if (focused) Modifier.border(1.dp, WhaleBlue, CircleShape) else Modifier)
        } else Modifier.dshaPressFeedback(interactions, shape = CircleShape, pressScale = .96f)
    CompositionLocalProvider(LocalGlassPress provides motion) {
        Box(
            modifier
                .size(size)
                .then(feedback)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                ) {
                    haptics.perform(UiHaptic.Selection)
                    onClick()
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (glass) RoundGlassFace(Modifier.size(size - 8.dp), size - 8.dp)
            content()
        }
    }
}

@Composable
internal fun RoundGlassFace(modifier: Modifier, diameter: Dp) {
    LiquidGlass(
        modifier
            .shadow(
                2.dp,
                CircleShape,
                clip = false,
                ambientColor = Color(0x180E1729),
                spotColor = Color(0x200E1729),
            )
            .clip(CircleShape),
        corner = diameter.value / 2f,
        shape = CircleShape,
    )
}
