package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun sortedProxyNodes(
    names: List<String>,
    delays: Map<String, Int>,
    sort: String,
    hideUnavailable: Boolean,
): List<String> {
    val visible = names.filterNot {
        hideUnavailable && delays[it]?.let { value -> value <= 0 } == true
    }
    return when (sort) {
        "延迟" ->
            visible.sortedWith(
                compareBy<String> {
                        delays[it]?.takeIf { ms -> ms > 0 }
                            ?: if (it in delays) Int.MAX_VALUE else Int.MAX_VALUE - 1
                    }
                    .thenBy { it.lowercase() },
            )
        "名称" -> visible.sortedBy { it.lowercase() }
        else -> visible
    }
}

@Composable
internal fun MihomoNodeCard(
    name: String,
    type: String,
    selected: Boolean,
    delay: Int?,
    enabled: Boolean,
    canMeasure: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onMeasure: () -> Unit,
) {
    val haptics = rememberUiHaptics()
    val interactions = remember { MutableInteractionSource() }
    val tint =
        when {
            delay == null -> DshaSurface.muted
            delay <= 0 -> DshaSurface.danger
            delay < 300 -> DshaSurface.success
            else -> Color(0xFFA67220)
        }
    DshaCard(
        modifier
            .dshaPressFeedback(interactions, pressScale = .98f)
            .selectable(
                selected,
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
            ) {
                if (!selected) {
                    haptics.perform(UiHaptic.Selection)
                    onSelect()
                }
            }
            .border(
                if (selected) 1.2.dp else 0.dp,
                if (selected) WhaleBlue.copy(alpha = .6f) else Color.Transparent,
                ContinuousShape(26.dp),
            ),
        padding = PaddingValues(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                type.ifBlank { "代理" },
                Modifier.weight(1f),
                fontSize = 10.sp,
                color = DshaSurface.muted,
                maxLines = 1,
            )
            if (selected) Icon(AppIcons.Check, "使用中", Modifier.size(17.dp), tint = WhaleBlue)
        }
        Text(
            name,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        DshaButton(
            onMeasure,
            enabled = canMeasure,
            style = DshaButtonStyle.Tonal,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Canvas(Modifier.size(5.dp)) { drawCircle(tint) }
            Spacer(Modifier.width(6.dp))
            Text(
                delay?.let { if (it > 0) "$it ms" else "不可用 · 重测" } ?: "测速",
                fontSize = 11.sp,
                color = tint,
            )
        }
    }
}
