package dev.deepseek.mobile

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/** Reach the bottom of a long final item, while streaming never snaps back to its first line. */
internal suspend fun LazyListState.scrollToConversationEnd() {
    withFrameNanos {}
    val last = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    if (layoutInfo.totalItemsCount == 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) scrollToItem(last)
    withFrameNanos {}
    val layout = layoutInfo
    val tail =
        layout.visibleItemsInfo.lastOrNull { it.index == layout.totalItemsCount - 1 } ?: return
    val remaining =
        (tail.offset.toLong() + tail.size + layout.afterContentPadding - layout.viewportEndOffset)
            .coerceAtLeast(0)
    if (remaining > 0) scrollBy(remaining.toFloat())
}
