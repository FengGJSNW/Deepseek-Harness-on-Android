package dev.deepseek.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val WhaleBlue = Color(0xFF426EFE)
internal val WhaleInk = Color(0xFF242424)
internal val WhaleMuted = Color(0xFF777777)
internal val WhaleLine = Color(0xFFE5E5E5)
internal val WhaleTint = Color(0xFFF4F7FE)
internal val WhaleMist = Color(0xFFF7F7F8)
internal val WhalePanelShape = ContinuousShape(20.dp)

/** The exact maid character, cropped only at draw time; never a system avatar. */
@Composable
internal fun JingAvatar(size: Dp = 34.dp, label: String = "小鲸鱼娘头像") {
    Box(
        Modifier.size(size)
            .clip(CircleShape)
            .background(Color(0xFFEDF2FF))
            .border(1.dp, Color(0xFFDFE7F9), CircleShape),
    ) {
        Image(
            painterResource(R.drawable.whale_chibi),
            label,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.35f
                scaleY = 1.35f
                translationX = -size.toPx() * .06f
                translationY = size.toPx() * .05f
            },
        )
    }
}

/** All geometry here is drawn in code. No platform checkbox, radio, switch or emoji glyph. */
@Composable
internal fun WhaleChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(ContinuousShape(16.dp))
            .background(if (selected) Color(0xFFF0F4FF) else Color.White)
            .border(1.dp, if (selected) Color(0xFFC8D6FF) else WhaleLine, ContinuousShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            drawCircle(
                if (selected) WhaleBlue else Color(0xFFBBC4D4),
                radius = 8.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
            if (selected) drawCircle(WhaleBlue, radius = 4.dp.toPx())
        }
        Text(
            label,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = WhaleInk,
        )
    }
}

@Composable
internal fun WhaleToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val color by
        animateColorAsState(if (checked) WhaleBlue else Color(0xFFDCE2ED), label = "toggle-color")
    val shift by animateDpAsState(if (checked) 22.dp else 2.dp, label = "toggle-knob")
    Box(
        Modifier.size(48.dp)
            .semantics {
                contentDescription = label
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .clip(ContinuousShape(16.dp))
            .clickable(role = Role.Switch) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(46.dp, 26.dp).background(color, ContinuousShape(13.dp))) {
            Box(
                Modifier.padding(top = 2.dp)
                    .offset(x = shift)
                    .size(22.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
internal fun WhaleSearch(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFFF0F0F0), ContinuousShape(10.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, Modifier.size(17.dp), tint = WhaleMuted)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = WhaleInk),
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("搜索对话", fontSize = 13.sp, color = WhaleMuted)
                    inner()
                }
            },
            visualTransformation = UprightSymbols.transformation,
        )
    }
}

@Composable
internal fun PromptCard(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(ContinuousShape(20.dp))
            .background(Color.White)
            .border(1.dp, WhaleLine, ContinuousShape(20.dp))
            .clickable(onClick = onClick)
            .padding(15.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(WhaleTint, ContinuousShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = WhaleBlue)
        }
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = WhaleInk,
            modifier = Modifier.padding(top = 13.dp),
        )
        Text(
            detail,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = WhaleMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A tiny, hand-drawn seam: decorative only and deliberately low contrast. */
@Composable
internal fun TideLine(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val wave =
            Path().apply {
                moveTo(0f, size.height * .6f)
                cubicTo(
                    size.width * .22f,
                    -size.height * .2f,
                    size.width * .34f,
                    size.height * 1.3f,
                    size.width * .53f,
                    size.height * .55f,
                )
                cubicTo(
                    size.width * .72f,
                    -size.height * .1f,
                    size.width * .85f,
                    size.height * .95f,
                    size.width,
                    size.height * .35f,
                )
            }
        drawPath(wave, Color(0xFFCCDAFA), style = Stroke(1.2.dp.toPx()))
        drawCircle(
            WhaleBlue.copy(alpha = .35f),
            1.6.dp.toPx(),
            Offset(size.width * .82f, size.height * .14f),
        )
    }
}

/** Centered option island, with a bounded scroll area on compact screens. */
@Composable
internal fun WhaleSheet(
    title: String,
    detail: String = "",
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CenterIsland(close) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    Modifier.weight(1f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = close, modifier = Modifier.size(32.dp)) {
                    Icon(AppIcons.Close, "关闭面板", Modifier.size(18.dp), tint = WhaleMuted)
                }
            }
            if (detail.isNotEmpty())
                Text(
                    detail,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = WhaleMuted,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            content()
        }
    }
}
