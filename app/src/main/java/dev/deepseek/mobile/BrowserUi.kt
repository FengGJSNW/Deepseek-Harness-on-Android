package dev.deepseek.mobile

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

internal fun closeBrowserTab(id: String) {
    val tab = BrowserState.tabs.firstOrNull { it.id == id } ?: return
    BrowserState.tabs.remove(tab)
    tab.navigation?.completeExceptionally(IllegalStateException("网页标签已关闭"))
    (tab.view.parent as? ViewGroup)?.removeView(tab.view)
    tab.view.destroy()
}

@Composable
internal fun BrowserProviderButtons(conversation: Conversation, showing: BrowserProvider) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserProvider.entries.forEach { provider ->
                FilterChip(
                    selected = showing == provider,
                    onClick = {
                        if (conversation.browserProvider != provider) {
                            conversation.browserProvider = provider
                            AssistantState.persist()
                        }
                        if (showing != provider) WorkspaceState.openBrowser()
                    },
                    label = { Text(provider.label) },
                    modifier = Modifier.testTag("browser-provider-${provider.key}"),
                )
            }
        }
        val active = AssistantState.running?.takeIf { it.sessionId == conversation.id }
        if (active != null && active.browserProvider != conversation.browserProvider.key) {
            Text(
                "当前任务使用${BrowserProvider.parse(active.browserProvider).label}，切换对新发送的任务生效。",
                Modifier.padding(bottom = 6.dp),
                fontSize = 11.sp,
                color = WhaleMuted,
            )
        } else if (showing != conversation.browserProvider) {
            Text(
                "新发送的任务使用${conversation.browserProvider.label}。",
                Modifier.padding(bottom = 6.dp),
                fontSize = 11.sp,
                color = WhaleMuted,
            )
        }
    }
    HorizontalDivider(color = WhaleLine)
}

@Composable
internal fun BrowserWorkspace(
    conversation: Conversation,
    tab: BrowserTab?,
    tabs: List<BrowserTab>,
) {
    Column(Modifier.fillMaxSize()) {
        BrowserProviderButtons(conversation, BrowserProvider.LOCAL)
        LocalBrowserPage(tab, tabs)
    }
}

@Composable
private fun LocalBrowserPage(tab: BrowserTab?, tabs: List<BrowserTab>) {
    if (tab == null) {
        Text(
            "Agent 打开的网页会显示在这里。",
            Modifier.padding(20.dp),
            fontSize = 13.sp,
            color = WhaleMuted,
        )
        return
    }
    key(tab.id) {
        Column(Modifier.fillMaxSize().testTag("workspace-browser")) {
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallIcon(AppIcons.ArrowBack, "后退", tab.view.canGoBack()) { tab.view.goBack() }
                IconButton(onClick = { tab.view.goForward() }, enabled = tab.view.canGoForward()) {
                    Icon(
                        AppIcons.ArrowBack,
                        "前进",
                        Modifier.size(20.dp).graphicsLayer { rotationZ = 180f },
                    )
                }
                SmallIcon(AppIcons.Reset, "重新加载") { tab.view.reload() }
                SelectionContainer(
                    Modifier.weight(1f)
                        .padding(end = 8.dp)
                        .background(WhaleMist, ContinuousShape(16.dp))
                        .padding(8.dp),
                ) {
                    Text(
                        tab.url.ifBlank { tab.title.ifBlank { "网页" } },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = WhaleMuted,
                    )
                }
                if (tabs.size > 1) {
                    var choosing by remember { mutableStateOf(false) }
                    Box {
                        SmallIcon(AppIcons.MoreHoriz, "选择网页") { choosing = true }
                        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            tabs.forEach { page ->
                                DropdownMenuItem(
                                    text = { Text(page.title.ifBlank { "网页" }, maxLines = 1) },
                                    onClick = {
                                        choosing = false
                                        WorkspaceState.open(WorkspaceTab.Web(page.id))
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (tab.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else HorizontalDivider(color = WhaleLine)
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        (tab.view.parent as? ViewGroup)?.removeView(tab.view)
                        addView(tab.view, FrameLayout.LayoutParams(-1, -1))
                    }
                },
                onRelease = { frame ->
                    if (tab.view.parent === frame) frame.removeView(tab.view)
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
