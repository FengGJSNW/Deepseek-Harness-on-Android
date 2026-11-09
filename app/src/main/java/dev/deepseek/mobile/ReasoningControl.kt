package dev.deepseek.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

internal data class EffortStop(val value: String, val title: String, val detail: String)

internal val effortStops =
    listOf(
        EffortStop("low", "Low", "轻量思考"),
        EffortStop("high", "High", "深入思考"),
        EffortStop("max", "Max", "最高思考强度"),
        EffortStop("ultra", "Ultra", "主智能体 Max · 可启动子智能体"),
    )

internal fun normalizeEffort(value: String, child: Boolean = false): String =
    when (value) {
        "medium" -> "high"
        "ultra" -> if (child) "max" else "ultra"
        "low",
        "high",
        "max" -> value
        else -> "low"
    }

/** Both islands share a spring: the root moves up and recedes as its child appears. */
@Composable
internal fun ReasoningCard(c: Conversation, modifier: Modifier) {
    var child by remember(c.id) { mutableStateOf(false) }
    val depth by
        animateFloatAsState(if (child) 1f else 0f, spring(.86f, 420f), label = "model-depth")
    val shape = ContinuousShape(18.dp)
    Box(modifier.testTag("reasoning-panel").padding(top = (14f * depth.coerceIn(0f, 1f)).dp)) {
        Surface(
            Modifier.fillMaxWidth()
                .graphicsLayer {
                    scaleX = 1f - depth * .045f
                    scaleY = 1f - depth * .045f
                    translationY = -depth * 14.dp.toPx()
                }
                .floatingIslandShadow(shape)
                .then(if (child) Modifier.clearAndSetSemantics {} else Modifier)
                .testTag("root-model-panel"),
            color = lerp(Color.White, Color(0xFFEFF1F5), depth.coerceIn(0f, 1f) * .7f),
            shape = shape,
            border = BorderStroke(.7.dp, WhaleLine),
        ) {
            Box(Modifier.graphicsLayer { alpha = (1f - depth).coerceIn(0f, 1f) }) {
                ModelEffortBody(c, false, enabled = !child, showChildren = { child = true })
            }
        }
        if (child || depth > .001f)
            Surface(
                Modifier.fillMaxWidth()
                    .graphicsLayer {
                        scaleX = .96f + depth * .04f
                        scaleY = .96f + depth * .04f
                        translationY = (1f - depth) * 6.dp.toPx()
                    }
                    .floatingIslandShadow(shape) { depth.coerceIn(0f, 1f) }
                    .then(if (!child) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("child-model-panel"),
                color = Color.White.copy(alpha = depth.coerceIn(0f, 1f)),
                shape = shape,
                border = BorderStroke(.7.dp, WhaleLine.copy(alpha = depth.coerceIn(0f, 1f))),
            ) {
                Box(Modifier.graphicsLayer { alpha = depth.coerceIn(0f, 1f) }) {
                    ModelEffortBody(c, true, enabled = child, back = { child = false })
                }
            }
    }
}

@Composable
private fun ModelEffortBody(
    c: Conversation,
    child: Boolean,
    enabled: Boolean = true,
    showChildren: () -> Unit = {},
    back: () -> Unit = {},
) {
    val stops = if (child) effortStops.take(3) else effortStops
    val effort = if (child) c.childEffort else c.effort
    val model = if (child) c.childModel else c.model
    var previewIndex by remember(c.id, child) { mutableStateOf<Int?>(null) }
    val index = previewIndex ?: stops.indexOfFirst { it.value == effort }.coerceAtLeast(0)
    var modelExpanded by remember { mutableStateOf(false) }
    val accent = composerEffortColor(stops[index].value)
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp)) {
                if (child)
                    IconButton(
                        onClick = {
                            modelExpanded = false
                            back()
                        },
                        enabled = enabled,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(AppIcons.ArrowBack, "返回主智能体设置", Modifier.size(20.dp), tint = WhaleInk)
                    }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stops[index].title,
                    color = accent,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier.testTag(if (child) "child-effort-title" else "effort-title"),
                )
                Row(
                    Modifier.clip(ContinuousShape(6.dp))
                        .clickable(enabled = enabled) { modelExpanded = !modelExpanded }
                        .padding(horizontal = 2.dp)
                        .semantics { contentDescription = if (child) "选择子智能体模型" else "选择模型" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (model == "deepseek-flash") "DeepSeek Flash" else "DeepSeek Pro",
                        color = WhaleMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        if (modelExpanded) AppIcons.ExpandMore else AppIcons.ChevronRight,
                        null,
                        Modifier.size(12.dp),
                        tint = WhaleMuted,
                    )
                }
            }
            IconButton(
                onClick = {
                    modelExpanded = false
                    if (!child) showChildren()
                },
                enabled = enabled && !child,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    AppIcons.Agents,
                    if (child) "子智能体设置" else "子智能体模型与强度",
                    Modifier.size(20.dp),
                    tint = if (child) WhaleMuted else WhaleInk,
                )
            }
        }
        if (modelExpanded)
            Column(Modifier.fillMaxWidth()) {
                listOf("deepseek-flash" to "DeepSeek Flash", "deepseek-v4-pro" to "DeepSeek Pro")
                    .forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .height(40.dp)
                                .clip(ContinuousShape(10.dp))
                                .background(if (model == value) WhaleTint else Color.Transparent)
                                .clickable {
                                    if (child) c.childModel = value else c.model = value
                                    AssistantState.persist()
                                    modelExpanded = false
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, Modifier.weight(1f), color = WhaleInk, fontSize = 13.sp)
                            if (model == value)
                                Icon(AppIcons.Check, null, Modifier.size(17.dp), tint = accent)
                        }
                    }
            }
        EffortRail(
            index,
            { previewIndex = it },
            {
                previewIndex = null
                if (child) c.childEffort = stops[it].value else c.effort = stops[it].value
                AssistantState.persist()
            },
            { previewIndex = null },
            Modifier.fillMaxWidth().height(36.dp),
            stops,
            enabled = enabled,
        )
    }
}
