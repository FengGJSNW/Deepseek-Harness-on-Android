package dev.deepseek.mobile

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*

internal class DrawerSearchController {
    val motion = DrawerSearchMotion()
    var visible by mutableStateOf(false)
    var query by mutableStateOf("")
    var origin by mutableStateOf(Rect.Zero)
    var backdrop: ChatBackdrop? = null
    var openResult: ((DrawerSearchItem) -> Unit)? = null

    fun show(bounds: Rect, open: (DrawerSearchItem) -> Unit) {
        origin = bounds
        openResult = open
        query = ""
        visible = true
    }

    fun close() {
        visible = false
    }
}

internal val LocalDrawerSearch = staticCompositionLocalOf<DrawerSearchController?> { null }

/** Whole-app blur is separate from the search controls, so text and results stay crisp. */
internal fun Modifier.searchBackgroundBlur(progress: State<Float>): Modifier = graphicsLayer {
    val radius = 16.dp.toPx() * progress.value.coerceIn(0f, 1f)
    renderEffect =
        if (Build.VERSION.SDK_INT >= 31 && radius > .1f) BlurEffect(radius, radius, TileMode.Clamp)
        else null
}

@Composable
internal fun DrawerSearchOverlay(search: DrawerSearchController, progress: State<Float>) {
    val visible = search.visible
    val t = progress.value.coerceIn(0f, 1f)
    if (!search.motion.retained) return
    val motion = search.motion
    val focus = remember { FocusRequester() }
    val manager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val index by rememberDrawerSearchIndex(search.motion.retained, AssistantState.appMode == "work")
    val close: () -> Unit = {
        search.close()
        manager.clearFocus()
        keyboard?.hide()
    }
    BackHandler(visible, close)
    LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(90)
            focus.requestFocus()
            keyboard?.show()
        }
    }
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { windowOrigin = it.positionInWindow() }
            .inactiveIf(!visible)
            .testTag("drawer-search-overlay"),
    ) {
        Box(
            Modifier.matchParentSize()
                .background(
                    Color(0xFFF2F2F0)
                        .copy(alpha = t * if (Build.VERSION.SDK_INT >= 31) .52f else .94f),
                )
                .clickable(
                    remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
        )

        BoxWithConstraints(
            Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft,
        ) {
            val density = LocalDensity.current
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val at = search.origin.translate(-windowOrigin)
            if (at.isEmpty) return@BoxWithConstraints
            val anchor =
                Rect(
                    at.left / density.density,
                    at.top / density.density,
                    at.right / density.density,
                    at.bottom / density.density,
                )
            val insets = WindowInsets.displayCutout
            val left = 12f + insets.getLeft(density, LocalLayoutDirection.current) / density.density
            val right =
                maxWidth.value -
                    12f -
                    insets.getRight(density, LocalLayoutDirection.current) / density.density
            val frame = motion.frame
            val pose = searchBarPose(anchor, left, right, rtl, frame)
            val fieldPress = rememberGlassPress()
            val cancelPress = rememberGlassPress()
            val field = pose.field
            val fieldFace = field.withPress(fieldPress, density.density)
            val cancelFace =
                pose.cancel.stretched(40f / 48f).withPress(cancelPress, density.density)
            val bandTop = minOf(fieldFace.top, cancelFace.top) - 12f
            val bandBottom = maxOf(fieldFace.bottom, cancelFace.bottom) + 12f
            CompositionLocalProvider(LocalGlassBackdrop provides search.backdrop) {
                if (frame.source > 0f) {
                    val home = anchor.deflate(4f)
                    Box(
                        Modifier.atSearchRect(home).graphicsLayer {
                            val shift = pose.source.center - home.center
                            translationX = shift.x.dp.toPx()
                            translationY = shift.y.dp.toPx()
                            scaleX = pose.source.width / home.width
                            scaleY = scaleX
                            alpha = frame.source
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        RoundGlassFace(Modifier.matchParentSize(), home.width.dp)
                        Icon(
                            AppIcons.Search,
                            null,
                            Modifier.size(22.dp).searchInk(frame.glyph),
                            tint = WhaleInk,
                        )
                    }
                }
                PrismGlassPane(
                    Modifier.fillMaxWidth()
                        .absoluteOffset(y = bandTop.dp)
                        .height((bandBottom - bandTop).dp),
                    fieldFace.translate(Offset(0f, -bandTop)),
                    fieldFace.height / 2f,
                    cancelFace.takeUnless { it.isEmpty }?.translate(Offset(0f, -bandTop)),
                    8f +
                        8f * maxOf(fieldPress.lift.value, cancelPress.lift.value).coerceAtLeast(0f),
                    frame.field,
                    2.dp,
                    GlassFinish.Regular,
                )
                Box(
                    Modifier.atSearchRect(field)
                        .glassPress(fieldPress)
                        .clip(ContinuousShape(26.dp))
                        .testTag("drawer-search-surface"),
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .requiredWidth(pose.target.width.dp)
                            .searchInk(frame.content * frame.field),
                    ) {
                        CompositionLocalProvider(LocalGlassPress provides fieldPress) {
                            PrismSearchField(
                                search.query,
                                { search.query = it },
                                "搜索会话、图片、文件和项目",
                                "搜索内容",
                                {
                                    focus.requestFocus()
                                    keyboard?.show()
                                },
                                Modifier.fillMaxSize(),
                                fieldModifier =
                                    Modifier.focusRequester(focus).testTag("drawer-search-field"),
                                enabled = visible,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            )
                        }
                    }
                }
                val cx = pose.cancel.center.x
                val cy = pose.cancel.center.y
                Box(
                    Modifier.absoluteOffset(x = (cx - 24f).dp, y = (cy - 24f).dp)
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = pose.cancel.width / 48f
                            scaleY = scaleX
                        }
                        .searchInk(frame.cancel * frame.field),
                ) {
                    CompositionLocalProvider(LocalGlassPress provides cancelPress) {
                        DshaIconButton(
                            "关闭搜索",
                            close,
                            modifier = Modifier.glassPress(cancelPress),
                            size = 48.dp,
                        ) {
                            Icon(AppIcons.Close, null, Modifier.size(21.dp), tint = WhaleInk)
                        }
                    }
                }
            }
            val resultsTop = pose.target.bottom + 12f
            DrawerSearchResults(
                index,
                search.query,
                DrawerSearchKind.ALL,
                Modifier.absoluteOffset(x = left.dp, y = resultsTop.dp)
                    .size(
                        (right - left).coerceAtLeast(1f).dp,
                        (maxHeight.value - resultsTop).coerceAtLeast(0f).dp,
                    )
                    .graphicsLayer { alpha = frame.content },
            ) { item ->
                search.openResult?.invoke(item)
            }
        }
    }
}

private fun Modifier.atSearchRect(r: Rect) =
    absoluteOffset(x = r.left.dp, y = r.top.dp)
        .size(r.width.coerceAtLeast(1f).dp, r.height.coerceAtLeast(1f).dp)

private fun Rect.withPress(press: GlassPressMotion, density: Float): Rect {
    val pose = press.transform()
    return stretched(pose.scaleX, pose.scaleY, pose.offset / density)
}

private fun Modifier.searchInk(shown: Float) = graphicsLayer {
    alpha = shown
    val blur = 6.dp.toPx() * (1f - shown)
    renderEffect =
        if (Build.VERSION.SDK_INT >= 31 && shown > 0f && blur > .1f)
            BlurEffect(blur, blur, TileMode.Decal)
        else null
}
