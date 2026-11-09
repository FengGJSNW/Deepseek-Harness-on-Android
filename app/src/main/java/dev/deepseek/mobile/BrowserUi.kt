package dev.deepseek.mobile

import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
internal fun BrowserWorkspace(tab: BrowserTab?) {
    if (tab == null) {
        Text("Agent 打开的网页会显示在这里。", Modifier.padding(20.dp), fontSize = 13.sp, color = WhaleMuted)
        return
    }
    key(tab.id) {
        val chrome = ExperienceSettings.browserMode.chrome
        val canTouch = chrome && tab.userAccess
        Column(Modifier.fillMaxSize().testTag("workspace-browser")) {
            if (tab.userOwned) UserBrowserAddress(tab, chrome)
            else
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color.White)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            tab.url.ifBlank { "网页" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(
                        enabled = chrome,
                        onClick = {
                            tab.userAccess = !tab.userAccess
                            tab.snapshot = ""
                        },
                    ) {
                        Text(if (tab.userAccess) "锁定" else "访问")
                    }
                }
            if (tab.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else HorizontalDivider(color = WhaleLine)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { context ->
                        object : FrameLayout(context) {
                                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                                    if (!tab.userAccess || !ExperienceSettings.browserMode.chrome)
                                        return true
                                    if (!tab.userOwned) {
                                        if (event.actionMasked == MotionEvent.ACTION_DOWN)
                                            tab.userTouchActive = true
                                        if (
                                            event.actionMasked in
                                                setOf(
                                                    MotionEvent.ACTION_UP,
                                                    MotionEvent.ACTION_CANCEL,
                                                )
                                        )
                                            tab.userTouchActive = false
                                        tab.snapshot = ""
                                    }
                                    return super.dispatchTouchEvent(event)
                                }

                                override fun dispatchGenericMotionEvent(
                                    event: MotionEvent,
                                ): Boolean =
                                    !tab.userAccess ||
                                        !ExperienceSettings.browserMode.chrome ||
                                        super.dispatchGenericMotionEvent(event)
                            }
                            .apply {
                                (tab.view.parent as? ViewGroup)?.removeView(tab.view)
                                addView(tab.view, FrameLayout.LayoutParams(-1, -1))
                            }
                    },
                    update = { frame ->
                        if (!canTouch) {
                            tab.userTouchActive = false
                            tab.view.clearFocus()
                        }
                        frame.descendantFocusability =
                            if (canTouch) ViewGroup.FOCUS_AFTER_DESCENDANTS
                            else ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        frame.importantForAccessibility =
                            if (canTouch) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    },
                    onRelease = { frame ->
                        tab.userTouchActive = false
                        if (tab.view.parent === frame) frame.removeView(tab.view)
                    },
                    modifier = Modifier.matchParentSize(),
                )
                if (!canTouch && !tab.userOwned)
                    Surface(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        shape = ContinuousShape(10.dp),
                        color = WhaleMist,
                    ) {
                        Text(
                            "不可操作",
                            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = WhaleMuted,
                            fontSize = 11.sp,
                        )
                    }
            }
        }
    }
}

@Composable
private fun UserBrowserAddress(tab: BrowserTab, enabled: Boolean) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf(tab.url) }
    var error by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tab.url) { if (tab.url.isNotBlank()) draft = tab.url }
    fun go() {
        if (!enabled || draft.isBlank()) return
        val input = draft.trim()
        val url =
            when {
                "://" in input -> input
                input.none(Char::isWhitespace) &&
                    ('.' in input.substringBefore('/') || input.startsWith("localhost")) ->
                    "https://$input"
                else ->
                    Uri.Builder()
                        .scheme("https")
                        .authority("www.google.com")
                        .path("search")
                        .appendQueryParameter("q", input)
                        .build()
                        .toString()
            }
        runCatching { tab.view.loadUrl(AgentBrowser(context).checkedUrl(url)) }
            .onSuccess {
                error = ""
                focus.clearFocus()
            }
            .onFailure { error = it.message.orEmpty() }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallIcon(AppIcons.ArrowBack, "后退", enabled && tab.view.canGoBack()) { tab.view.goBack() }
        OutlinedTextField(
            draft,
            { draft = it.take(4096) },
            Modifier.weight(1f).testTag("user-browser-address"),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("输入网址或关键词") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
        )
        TextButton(enabled = enabled && draft.isNotBlank(), onClick = { go() }) { Text("前往") }
    }
    if (!enabled)
        Text("请在设置中启用 Chrome 浏览。", Modifier.padding(12.dp), color = WhaleMuted, fontSize = 12.sp)
    if (error.isNotBlank())
        Text(
            error,
            Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
}
