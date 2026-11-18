package dev.deepseek.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** Surface roles shared by settings, tools and the proxy dashboard. */
internal object DshaSurface {
    val page = Color(0xFFF2F2F6)
    val card = Color(0xFFFEFDFE)
    val inset = Color(0xFFF0EFF4)
    val muted = Color(0xFF787B89)
    val success = Color(0xFF23865B)
    val danger = Color(0xFFBD4C55)
    val corner = 24.dp
}

internal val LocalDshaCard = staticCompositionLocalOf { false }

@Composable
internal fun DshaCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    color: Color = DshaSurface.card,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember { ContinuousShape(DshaSurface.corner) }
    Column(
        modifier.fillMaxWidth().clip(shape).background(color).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        CompositionLocalProvider(LocalDshaCard provides true) { content() }
    }
}

internal enum class DshaButtonStyle {
    Filled,
    Tonal,
    Plain,
    Danger,
}

@Composable
internal fun DshaTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) = DshaButton(onClick, modifier, enabled, DshaButtonStyle.Plain, contentPadding, content = content)

@Composable
internal fun DshaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: DshaButtonStyle = DshaButtonStyle.Filled,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
    shape: Shape = ContinuousShape(50.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val haptics = rememberUiHaptics()
    val fill =
        when (style) {
            DshaButtonStyle.Filled -> WhaleBlue
            DshaButtonStyle.Tonal -> DshaSurface.inset
            DshaButtonStyle.Plain -> Color.Transparent
            DshaButtonStyle.Danger -> DshaSurface.danger.copy(alpha = .09f)
        }
    val ink =
        when (style) {
            DshaButtonStyle.Filled -> Color.White
            DshaButtonStyle.Danger -> DshaSurface.danger
            else -> WhaleInk
        }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .dshaPressFeedback(
                interactions,
                if (enabled) fill else DshaSurface.inset,
                shape,
                pressScale = .965f,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
            ) {
                haptics.perform(UiHaptic.Selection)
                onClick()
            }
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) ink else DshaSurface.muted.copy(alpha = .45f),
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun DshaCardHeader(
    icon: ImageVector,
    title: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = DshaSurface.muted)
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = WhaleInk,
        )
        trailing()
    }
}

@Composable
internal fun <T> DshaSegments(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionTag: ((T) -> String)? = null,
) {
    val haptics = rememberUiHaptics()
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, (value, label) ->
            val chosen = selected == value
            val interactions = remember { MutableInteractionSource() }
            val shape =
                ContinuousShape(
                    topStart = if (i == 0) 22.dp else 9.dp,
                    bottomStart = if (i == 0) 22.dp else 9.dp,
                    topEnd = if (i == options.lastIndex) 22.dp else 9.dp,
                    bottomEnd = if (i == options.lastIndex) 22.dp else 9.dp,
                )
            val color by
                androidx.compose.animation.animateColorAsState(
                    if (chosen) WhaleBlue else DshaSurface.inset,
                    tween(180),
                    label = "segment-fill",
                )
            Box(
                Modifier.weight(1f)
                    .then(optionTag?.let { Modifier.testTag(it(value)) } ?: Modifier)
                    .heightIn(min = 44.dp)
                    .dshaPressFeedback(
                        interactions,
                        if (enabled) color else DshaSurface.inset,
                        shape,
                        pressScale = .975f,
                    )
                    .selectable(
                        selected = chosen,
                        enabled = enabled,
                        role = Role.RadioButton,
                        interactionSource = interactions,
                        indication = null,
                    ) {
                        if (!chosen) {
                            haptics.perform(UiHaptic.Selection)
                            onSelect(value)
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (!enabled) DshaSurface.muted else if (chosen) Color.White else WhaleInk,
                )
            }
        }
    }
}
