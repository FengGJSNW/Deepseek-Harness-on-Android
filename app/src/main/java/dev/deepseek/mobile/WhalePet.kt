package dev.deepseek.mobile

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
private fun AnimatedWhale(resource: Int, modifier: Modifier) {
    val context = LocalContext.current
    val drawable =
        remember(resource) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resource))
        }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(drawable, lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START ->
                        (drawable as? AnimatedImageDrawable)?.start()
                    androidx.lifecycle.Lifecycle.Event.ON_STOP ->
                        (drawable as? AnimatedImageDrawable)?.stop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            (drawable as? AnimatedImageDrawable)?.start()
        onDispose {
            lifecycle.removeObserver(observer)
            (drawable as? AnimatedImageDrawable)?.stop()
        }
    }
    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        modifier = modifier,
        update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhalePet(
    size: Int,
    onDetails: () -> Unit = {},
    gesture: Modifier = Modifier,
    style: String = "ohjing",
    showBubble: Boolean = true,
    bubbleOnRight: Boolean = false,
    bubbleOffsetX: () -> Int = { 0 },
    facingRight: Boolean = false,
    patrolCompression: () -> Float = { 0f },
) {
    val pet = WhaleCompanion
    val inspecting = LocalInspectionMode.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var wasPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            wasPressed = true
            WhaleSounds.press()
        } else if (wasPressed) {
            wasPressed = false
            WhaleSounds.releaseTap()
        }
    }
    DisposableEffect(Unit) { onDispose { if (wasPressed) WhaleSounds.releaseTap() } }
    val compression = remember { Animatable(0f) }
    val spark = remember { Animatable(1f) }
    LaunchedEffect(pet.touches) {
        if (pet.touches > 0) {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(720, easing = LinearOutSlowInEasing))
        }
    }
    LaunchedEffect(pressed, pet.touches) {
        if (pressed) compression.animateTo(1f, tween(70))
        else {
            if (pet.touches > 0 && compression.value < .1f) compression.snapTo(.65f)
            compression.animateTo(0f, spring(dampingRatio = .4f, stiffness = 500f))
        }
    }
    LaunchedEffect(pet.revision) {
        if (pet.text.isNotBlank()) {
            delay(if (pet.canUseFlash) 15000 else 6500)
            pet.settle()
        }
    }
    // Tiny breathing movement for the original still, while the alternative plays real source
    // frames.
    val breath = remember { Animatable(0f) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, inspecting) {
        if (!inspecting)
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    breath.animateTo(1f, tween(1800))
                    breath.animateTo(0f, tween(2200))
                    delay(1800)
                }
            }
    }
    LaunchedEffect(Unit) {
        if (!inspecting && pet.revision == 0) {
            delay(500)
            if (AssistantState.running == null) pet.react("idle", "哦鲸鲸…")
        }
    }
    val quiet = pet.resting && AssistantState.running == null
    val thoughtShape =
        remember(bubbleOnRight) {
            GenericShape { size, _ ->
                val w = size.width
                val h = size.height
                fun x(fraction: Float) = w * if (bubbleOnRight) fraction else 1f - fraction
                moveTo(x(.10f), h * .72f)
                cubicTo(x(-.02f), h * .64f, x(.01f), h * .35f, x(.12f), h * .30f)
                cubicTo(x(.08f), h * .10f, x(.28f), -h * .01f, x(.43f), h * .10f)
                cubicTo(x(.58f), -h * .02f, x(.80f), h * .03f, x(.83f), h * .20f)
                cubicTo(x(1.03f), h * .18f, x(1.04f), h * .49f, x(.95f), h * .56f)
                cubicTo(x(1.02f), h * .82f, x(.78f), h * .97f, x(.60f), h * .90f)
                cubicTo(x(.52f), h * 1.01f, x(.30f), h * .98f, x(.26f), h * .89f)
                cubicTo(x(.17f), h * .94f, x(.05f), h * .87f, x(.10f), h * .72f)
                close()
            }
        }
    Box(
        Modifier.width(if (showBubble) 220.dp else size.dp)
            .height(
                if (showBubble)
                    (size + if (pet.sticker != 0 || pet.customSticker.isNotBlank()) 240 else 180).dp
                else size.dp,
            ),
    ) {
        AnimatedVisibility(
            showBubble && pet.text.isNotBlank(),
            enter = fadeIn(tween(160)) + scaleIn(initialScale = .88f),
            exit = fadeOut(tween(180)),
            modifier =
                Modifier.align(if (bubbleOnRight) Alignment.BottomEnd else Alignment.BottomStart)
                    .offset { androidx.compose.ui.unit.IntOffset(bubbleOffsetX(), 0) }
                    .padding(
                        start = if (bubbleOnRight) 0.dp else 8.dp,
                        end = if (bubbleOnRight) 8.dp else 0.dp,
                        bottom = (size * .98f).dp,
                    )
                    .testTag("pet-bubble"),
        ) {
            Column(horizontalAlignment = if (bubbleOnRight) Alignment.Start else Alignment.End) {
                Surface(
                    color = Color(0xFFFEFFFF),
                    shape = thoughtShape,
                    border = BorderStroke(1.7.dp, Color(0xFF293858)),
                    shadowElevation = 0.dp,
                    modifier = Modifier.width(174.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 25.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (pet.sticker != 0)
                            Image(
                                painterResource(pet.sticker),
                                null,
                                Modifier.size(68.dp).clip(ContinuousShape(12.dp)),
                            )
                        if (pet.customSticker.isNotBlank()) CustomBubbleSticker(pet.customSticker)
                        Text(
                            if (pet.styledText.text == pet.text) pet.styledText
                            else androidx.compose.ui.text.AnnotatedString(pet.text),
                            color = WhaleBubbleBlue,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            maxLines =
                                if (pet.sticker != 0 || pet.customSticker.isNotBlank()) 4 else 6,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Canvas(Modifier.padding(horizontal = 9.dp).size(32.dp, 20.dp)) {
                    fun x(value: Float) = (if (bubbleOnRight) 32f - value else value).dp.toPx()
                    drawCircle(
                        Color(0xFF293858),
                        5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x(8f), 5.dp.toPx()),
                    )
                    drawCircle(
                        Color.White,
                        3.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x(8f), 5.dp.toPx()),
                    )
                    drawCircle(
                        Color(0xFF293858),
                        2.5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x(20f), 15.dp.toPx()),
                    )
                    drawCircle(
                        Color.White,
                        1.2.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x(20f), 15.dp.toPx()),
                    )
                }
            }
        }
        val body =
            Modifier.align(if (bubbleOnRight) Alignment.BottomStart else Alignment.BottomEnd)
                .size(size.dp)
                .then(gesture)
                .semantics { contentDescription = "哦鲸鲸，点击互动，可拖动" }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        WhaleActivity.interact()
                        pet.touch()
                    },
                    onClickLabel = "摸摸鲸鲸",
                )
                .graphicsLayer {
                    val v = (compression.value + patrolCompression()).coerceIn(-.4f, 1f)
                    transformOrigin = TransformOrigin(.5f, 1f)
                    scaleX = (if (facingRight) -1f else 1f) * (1f + v * .05f)
                    scaleY = 1f - v * .12f + if (style == "ohjing") breath.value * .012f else 0f
                    rotationZ = if (quiet) -3f else 0f
                }
        if (style == "animated") AnimatedWhale(pet.animation(), body)
        else Image(painterResource(R.drawable.whale_chibi_expanded), null, body)
        // A hand-drawn four-point glint rises off the head when petted.
        Canvas(
            Modifier.align(if (bubbleOnRight) Alignment.BottomStart else Alignment.BottomEnd)
                .size(size.dp),
        ) {
            val t = spark.value
            if (t >= 1f) return@Canvas
            val fade = (1f - t).coerceIn(0f, 1f)
            listOf(Triple(.15f, .31f, 1f), Triple(.78f, .17f, .72f), Triple(.54f, .04f, .5f))
                .forEach { (x, y, k) ->
                    val cx = this.size.width * x
                    val cy = this.size.height * (y - t * .13f)
                    val r = 5.dp.toPx() * k * (.6f + t)
                    val star =
                        Path().apply {
                            moveTo(cx, cy - r)
                            quadraticBezierTo(cx + r * .2f, cy - r * .2f, cx + r, cy)
                            quadraticBezierTo(cx + r * .2f, cy + r * .2f, cx, cy + r)
                            quadraticBezierTo(cx - r * .2f, cy + r * .2f, cx - r, cy)
                            quadraticBezierTo(cx - r * .2f, cy - r * .2f, cx, cy - r)
                            close()
                        }
                    drawPath(star, Color(0xFF8AA4ED).copy(alpha = fade))
                }
        }
    }
}
