package dev.deepseek.mobile

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val DrawerSearchKind.icon: ImageVector
    get() =
        when (this) {
            DrawerSearchKind.ALL -> SearchIcons.All
            DrawerSearchKind.CHATS -> SearchIcons.Chats
            DrawerSearchKind.IMAGES -> SearchIcons.Images
            DrawerSearchKind.FILES -> SearchIcons.Files
            DrawerSearchKind.PROJECTS -> SearchIcons.Projects
        }

@Composable
internal fun DrawerSearchResults(
    index: DrawerSearchIndex,
    query: String,
    kind: DrawerSearchKind,
    modifier: Modifier = Modifier,
    onOpen: (DrawerSearchItem) -> Unit,
) {
    val results by
        produceState<List<DrawerSearchItem>?>(null, index, query, kind) {
            value = null
            if (query.isNotBlank()) delay(100)
            value = withContext(Dispatchers.Default) { searchDrawerItems(index.items, query, kind) }
        }
    val matches = results
    val loading = index.loading || matches == null
    LazyColumn(
        modifier.testTag("drawer-search-results"),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "search-heading") {
            Text(
                when {
                    loading -> "正在搜索…"
                    index.error.isNotEmpty() -> index.error
                    matches.isNullOrEmpty() ->
                        if (query.isBlank())
                            "暂无${kind.label.takeIf { kind != DrawerSearchKind.ALL } ?: "内容"}"
                        else "没有匹配的结果"
                    query.isBlank() ->
                        "最近${if (kind == DrawerSearchKind.ALL) "内容" else kind.label} · ${matches.size}"
                    else -> "搜索结果 · ${matches.size}"
                },
                Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                style = PrismTypography.footnote,
                color = WhaleMuted,
            )
        }
        if (!loading)
            items(matches.orEmpty(), key = { it.id }) { item ->
                DrawerSearchResultRow(item, query) { onOpen(item) }
            }
    }
}

@Composable
private fun DrawerSearchResultRow(item: DrawerSearchItem, query: String, onClick: () -> Unit) {
    val term = query.trim()
    val hit = if (term.isNotEmpty()) item.recognition.indexOf(term, ignoreCase = true) else -1
    val detail =
        if (hit >= 0) {
            val start = (hit - 12).coerceAtLeast(0)
            "识别：" +
                item.recognition
                    .substring(
                        start,
                        (hit + term.length + 70).coerceAtMost(item.recognition.length),
                    )
                    .replace('\n', ' ')
        } else item.detail
    val interactions = remember { MutableInteractionSource() }
    val haptics = rememberUiHaptics()
    Row(
        Modifier.fillMaxWidth()
            .dshaPressFeedback(interactions)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                haptics.perform(UiHaptic.Selection)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .heightIn(min = 48.dp)
            .testTag("drawer-search-result-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(item.kind.icon, item.kind.label, Modifier.size(22.dp), tint = WhaleMuted)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                item.title,
                style = PrismTypography.callout,
                color = WhaleInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = PrismTypography.caption,
                color = WhaleMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.kind == DrawerSearchKind.IMAGES) {
            Spacer(Modifier.width(8.dp))
            SearchImageThumbnail(item)
        }
    }
}

@Composable
private fun SearchImageThumbnail(item: DrawerSearchItem) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap by
        produceState<ImageBitmap?>(null, item.target, size) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file =
                            if (item.legacyImage)
                                File(context.filesDir, "screens/${item.target}.jpg")
                            else AttachmentStore(context).file(item.target)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        val options =
                            BitmapFactory.Options().apply {
                                var sample = 1
                                while (
                                    maxOf(bounds.outWidth, bounds.outHeight) / sample > size * 2
                                ) sample *= 2
                                inSampleSize = sample
                            }
                        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
                    }
                        .getOrNull()
                }
        }
    Box(
        Modifier.size(40.dp).clip(ContinuousShape(9.dp)).background(WhaleInk.copy(alpha = .04f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null)
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(SearchIcons.Images, null, Modifier.size(22.dp), tint = WhaleMuted)
    }
}
