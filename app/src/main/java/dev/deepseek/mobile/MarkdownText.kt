package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MarkdownText(raw: String, modifier: Modifier = Modifier, scrollable: Boolean = false) {
    val body = if (scrollable) modifier.heightIn(max = 380.dp) else modifier
    if (!raw.contains("<details", ignoreCase = true) && MathDocument.containsMath(raw)) {
        MathMessage(raw, body)
        return
    }
    val quick =
        remember(raw, FontSettings.revision, DshaFonts.ready) {
            if (raw.length <= 2048) MarkdownDocument.parse(raw) else MarkdownDocument.cached(raw)
        }
    val asynchronous by
        produceState(quick.orEmpty(), raw, FontSettings.revision, DshaFonts.ready) {
            value = quick ?: withContext(Dispatchers.Default) { MarkdownDocument.parse(raw) }
        }
    val blocks = quick ?: asynchronous
    MarkdownSelectionContainer(body.testTag("markdown-message")) {
        if (scrollable)
            LazyColumn(
                Modifier.testTag("markdown-fold-scroll"),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                itemsIndexed(blocks) { index, block ->
                    MarkdownBlocks(listOf(block), path = "/$index")
                }
            }
        else MarkdownBlocks(blocks)
    }
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    ink: Color = ReadingStyle.ink,
    compact: Boolean = false,
    completed: Boolean = false,
    path: String = "",
    quote: String = "",
    firstPrefix: String = "",
) {
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val prefix = quote + if (index == 0) firstPrefix else ""
            val childPath = "$path/$index"
            val gap =
                if (index == 0) 0.dp
                else
                    when (block) {
                        is MarkdownBlock.Heading -> 22.dp
                        is MarkdownBlock.Quote -> 15.dp
                        is MarkdownBlock.Code,
                        is MarkdownBlock.Table -> 12.dp
                        MarkdownBlock.Rule -> 18.dp
                        else -> if (compact) 4.dp else 9.dp
                    }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            when (block) {
                is MarkdownBlock.Paragraph ->
                    ProseText(
                        MarkdownSelection.block(block.text, "prefix:$prefix"),
                        color = ink,
                        balanced = compact,
                        decoration =
                            if (completed) androidx.compose.ui.text.style.TextDecoration.LineThrough
                            else null,
                    )
                is MarkdownBlock.Heading ->
                    ProseText(
                        MarkdownSelection.block(
                            block.text,
                            "prefix:$prefix${"#".repeat(block.level)} ",
                        ),
                        Modifier.testTag("markdown-heading"),
                        color = ink,
                        size =
                            when (block.level) {
                                1 -> 19.sp
                                2 -> 17.sp
                                3 -> 16.sp
                                else -> 15.sp
                            },
                        weight = FontWeight(580),
                    )
                is MarkdownBlock.Code -> MarkdownCode(block)
                is MarkdownBlock.Fold -> MarkdownFold(block, childPath)
                is MarkdownBlock.Formula -> MathMessage(block.raw)
                is MarkdownBlock.Quote ->
                    MarkdownBlocks(
                        block.children,
                        Modifier.fillMaxWidth()
                            .padding(start = 5.dp)
                            .drawBehind {
                                drawLine(
                                    ReadingStyle.line,
                                    Offset.Zero,
                                    Offset(0f, size.height),
                                    2.dp.toPx(),
                                )
                            }
                            .padding(start = 14.dp),
                        ink = ReadingStyle.secondary,
                        compact = true,
                        path = childPath,
                        quote = "$quote> ",
                        firstPrefix = if (index == 0) firstPrefix else "",
                    )
                is MarkdownBlock.Listing ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        block.items.forEachIndexed { itemIndex, item ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                DisableSelection {
                                    if (item.checked != null)
                                        Box(
                                            Modifier.padding(top = 4.dp)
                                                .size(13.dp)
                                                .background(
                                                    if (item.checked) ReadingStyle.secondary
                                                    else Color.Transparent,
                                                    ContinuousShape(3.dp),
                                                )
                                                .border(
                                                    .8.dp,
                                                    ReadingStyle.secondary.copy(alpha = .6f),
                                                    ContinuousShape(3.dp),
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (item.checked)
                                                Icon(
                                                    AppIcons.Check,
                                                    "已完成",
                                                    Modifier.size(10.dp),
                                                    tint = Color.White,
                                                )
                                        }
                                    else
                                        Text(
                                            item.marker,
                                            Modifier.widthIn(min = 17.dp),
                                            color = ReadingStyle.secondary,
                                            fontSize = 15.sp,
                                            lineHeight = (15 * ExperienceSettings.lineSpacing).sp,
                                        )
                                }
                                MarkdownBlocks(
                                    item.children,
                                    Modifier.weight(1f),
                                    ink = if (item.checked == true) ReadingStyle.secondary else ink,
                                    compact = true,
                                    completed = item.checked == true,
                                    path = "$childPath/$itemIndex",
                                    quote = quote,
                                    firstPrefix =
                                        when (item.checked) {
                                            true -> "- [x] "
                                            false -> "- [ ] "
                                            null ->
                                                if (item.marker == "•") "- " else "${item.marker} "
                                        },
                                )
                            }
                        }
                    }
                is MarkdownBlock.Table -> MarkdownTable(block, childPath)
                is MarkdownBlock.Image -> MarkdownImage(block)
                MarkdownBlock.Rule ->
                    HorizontalDivider(
                        Modifier.padding(bottom = 8.dp),
                        color = ReadingStyle.line,
                        thickness = .75.dp,
                    )
            }
        }
    }
}

@Composable
private fun MarkdownFold(block: MarkdownBlock.Fold, path: String) {
    var expanded by
        androidx.compose.runtime.saveable.rememberSaveable(path, block.title) {
            mutableStateOf(block.initiallyOpen)
        }
    ActivityCard(Modifier.testTag("markdown-fold")) {
        DisableSelection {
            DisclosureHeader(
                block.title.ifBlank { "详细内容" },
                expanded,
                Modifier.testTag("markdown-fold-toggle"),
                icon = AppIcons.Description,
            ) {
                expanded = !expanded
            }
        }
        DisclosureBody(expanded, Modifier.testTag("markdown-fold-body")) {
            MarkdownText(
                block.body,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                scrollable = true,
            )
        }
    }
}

@Composable
private fun MarkdownCode(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    val quick =
        remember(block.text, block.language) {
            if (block.text.length <= 4096) CodeHighlight.text(block.text, block.language) else null
        }
    val highlighted by
        produceState(quick ?: AnnotatedString(block.text), block.text, block.language) {
            value =
                quick
                    ?: withContext(Dispatchers.Default) {
                        CodeHighlight.text(block.text, block.language)
                    }
        }
    Surface(
        Modifier.fillMaxWidth().testTag("markdown-code"),
        color = Color(0xFFF5F4F1),
        shape = ContinuousShape(8.dp),
    ) {
        Column {
            var copied by remember(block.text) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }
            // Claude cached markup: caption and copy share the code surface, no toolbar divider.
            DisableSelection {
                Row(
                    Modifier.fillMaxWidth()
                        .height(32.dp)
                        .padding(start = 14.dp, end = 8.dp)
                        .testTag("code-caption"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        block.language.ifBlank { "纯文本" },
                        Modifier.weight(1f),
                        fontSize = 10.sp,
                        color = ReadingStyle.secondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (copied) Text("已复制", fontSize = 10.sp, color = ReadingStyle.secondary)
                    Box(
                        Modifier.size(32.dp).clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                        ) {
                            clipboard.setText(AnnotatedString(block.text))
                            copied = true
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.ContentCopy,
                            "复制代码",
                            Modifier.size(15.dp),
                            tint = ReadingStyle.secondary,
                        )
                    }
                }
            }
            val horizontal = rememberScrollState()
            val vertical = rememberScrollState()
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .contentScrollIndicators(horizontal, vertical)
                    .testTag("code-scroll-area"),
            ) {
                Text(
                    MarkdownSelection.block(highlighted, "code:${block.language}"),
                    Modifier.verticalScroll(vertical)
                        .horizontalScroll(horizontal)
                        .padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 14.dp),
                    color = ReadingStyle.ink,
                    fontFamily = DshaFonts.mono,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = DshaFonts.features),
                    fontSize = 13.sp,
                    lineHeight = (13f * ExperienceSettings.lineSpacing).sp,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun MarkdownImage(block: MarkdownBlock.Image) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var failed by remember(block.url) { mutableStateOf(false) }
    val permissions =
        remember(context) {
            dev.deepseek.mobile.harness.HarnessCoordinator.get(context).permissions
        }
    val revision = permissions.revision
    val current = AssistantState.current
    val policy =
        dev.deepseek.mobile.harness.PermissionContext(
            sessionId = current.id,
            projectId = current.projectId,
        )
    val model by
        produceState<Any?>(null, block.url, revision, current.id) {
            value =
                withContext(Dispatchers.IO) {
                    when {
                        block.url.startsWith("https://") &&
                            MarkdownDocument.safeLink(block.url) &&
                            permissions
                                .evaluate(
                                    policy,
                                    dev.deepseek.mobile.harness.Capability.NETWORK,
                                    block.url,
                                )
                                .effect == dev.deepseek.mobile.harness.PermissionEffect.ALLOW ->
                            block.url
                        block.url.startsWith("dsha://file/") &&
                            MarkdownDocument.safeLink(block.url) -> runCatching {
                                val store = AttachmentStore(context)
                                val id = block.url.substringAfterLast('/')
                                if (store.get(id).mime.startsWith("image/")) store.file(id)
                                else null
                            }
                                .getOrNull()
                        else -> null
                    }
                }
        }
    if (model != null && !failed)
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).size(1200, 1200).build(),
            contentDescription = block.alt.ifBlank { "消息图片" },
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp, max = 320.dp)
                    .clip(ContinuousShape(12.dp)),
        )
    else
        DshaTextButton(
            onClick = {
                if (block.url.startsWith("dsha://file/") && MarkdownDocument.safeLink(block.url))
                    FilePreviewState.open(block.url.substringAfterLast('/'))
                else if (MarkdownDocument.safeLink(block.url)) uri.openUri(block.url)
            },
            enabled = MarkdownDocument.safeLink(block.url),
        ) {
            Icon(AppIcons.Image, null, Modifier.size(17.dp))
            Text(block.alt.ifBlank { "查看图片" }, Modifier.padding(start = 7.dp), fontSize = 13.sp)
        }
}
