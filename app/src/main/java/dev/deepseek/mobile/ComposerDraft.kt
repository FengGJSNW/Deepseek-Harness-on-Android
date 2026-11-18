package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Grow with the text; the parent reserves room for the toolbar inside the half-screen limit. */
@Composable
internal fun ComposerDraft(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    inputTag: String,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val draftStyle =
        MaterialTheme.typography.bodyMedium.copy(
            fontFamily = DshaFonts.family(FontRole.BODY),
            color = WhaleInk,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp / 2
    val viewport = modifier.fillMaxWidth().heightIn(min = minOf(48.dp, maxHeight), max = maxHeight)
    Column(viewport) {
        if (preview) {
            Text(
                "Markdown 预览",
                Modifier.fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp)
                    .testTag("composer-preview-label"),
                color = WhaleMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
        // Keep the same editor composition and InputConnection while preview is inserted/removed.
        // This preserves the IME's composing range, cursor and selection during Chinese input.
        key(inputTag) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(inputTag),
                textStyle = draftStyle,
                cursorBrush = SolidColor(WhaleBlue),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty())
                            Text(placeholder, style = draftStyle, color = WhaleMuted)
                        inner()
                    }
                },
                visualTransformation =
                    if (preview) EditableMarkdown else UprightSymbols.transformation,
            )
        }
    }
}

@Composable
internal fun MarkdownPreviewToggle(
    preview: Boolean,
    enabled: Boolean,
    menuExpanded: Boolean? = null,
    onMenuExpandedChange: ((Boolean) -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    var localMenu by remember { mutableStateOf(false) }
    val menu = menuExpanded ?: localMenu
    val setMenu: (Boolean) -> Unit = { value ->
        if (onMenuExpandedChange != null) onMenuExpandedChange(value) else localMenu = value
    }
    LaunchedEffect(enabled) { if (!enabled) setMenu(false) }
    LiquidMenuAnchor(
        expanded = menu,
        close = { setMenu(false) },
        openOnPress = if (enabled) ({ setMenu(true) }) else null,
        width = 260.dp,
        toggleAnchor = true,
        anchor = {
            DshaIconButton(
                label = "Markdown 预览",
                onClick = { setMenu(!menu) },
                enabled = enabled,
                size = 44.dp,
                modifier =
                    Modifier.testTag("composer-preview-toggle").semantics {
                        stateDescription = if (preview) "实时预览已开启，可继续编辑" else "实时预览已关闭"
                        toggleableState =
                            if (preview) androidx.compose.ui.state.ToggleableState.On
                            else androidx.compose.ui.state.ToggleableState.Off
                    },
            ) {
                Icon(
                    AppIcons.Markdown,
                    null,
                    Modifier.size(22.dp).testTag("toolbar-Markdown"),
                    tint =
                        if (!enabled) WhaleMuted.copy(alpha = .5f)
                        else if (preview) WhaleBlue else WhaleMuted,
                )
            }
        },
    ) {
        GlassMenuRow(AppIcons.Markdown, "Markdown 预览", "渲染并继续编辑", selected = preview) {
            onChange(true)
            setMenu(false)
        }
        GlassMenuRow(AppIcons.Edit, "纯文本", "显示 Markdown 标记", selected = !preview) {
            onChange(false)
            setMenu(false)
        }
    }
}

/** Preview is a transient viewing mode; every foreground entry starts in editing mode. */
@Composable
internal fun rememberDraftPreview(key: Any = Unit): MutableState<Boolean> {
    val preview = remember(key) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, preview) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) preview.value = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return preview
}
