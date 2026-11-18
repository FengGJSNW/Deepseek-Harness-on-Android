package dev.deepseek.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HtmlWorkspace(file: LocalAttachment) {
    val context = LocalContext.current
    var reload by remember(file.id) { mutableIntStateOf(0) }
    var error by remember(file.id, reload) { mutableStateOf("") }
    val document by
        produceState<HtmlPreviewDocument?>(null, file.id, reload) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val store = AttachmentStore(context)
                        store.file(file.id)
                        HtmlPreviewDocument(store, file, store.all())
                    }
                        .getOrNull()
                }
            if (value == null) error = "文件已不可用，可重新发布后打开。"
        }
    Column(
        Modifier.fillMaxSize()
            .padding(top = LocalWorkspaceTopInset.current)
            .testTag("workspace-html"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "HTML 预览",
                Modifier.weight(1f),
                style = PrismTypography.footnote,
                color = WhaleMuted,
            )
            SmallIcon(AppIcons.Reset, "刷新 HTML 预览") { reload++ }
            SmallIcon(AppIcons.Description, "查看 HTML 源码") {
                WorkspaceState.open(WorkspaceTab.Document(file))
            }
            SmallIcon(AppIcons.Download, "导出 HTML") {
                MainActivity.current?.exportAttachment(file.id)
            }
        }
        val content = document
        if (error.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, color = WhaleMuted)
                    DshaTextButton(onClick = { reload++ }) { Text("重新加载") }
                }
            }
        } else if (content == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else
            key(file.id, reload) {
                HtmlPreviewView(content, Modifier.fillMaxSize()) { error = it }
            }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreviewView(
    document: HtmlPreviewDocument,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onError)
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).apply {
                tag = "html-preview-webview"
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                // No Android/Agent bridge, file chooser, permissions or download handler is
                // exposed.
                webChromeClient = WebChromeClient()
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (request.url.host == document.host)
                                return document.response(request.url, request.method)
                            // HTTPS resources (e.g. a stylesheet/CDN) use the normal browser
                            // sandbox.
                            val host = request.url.host.orEmpty()
                            if (
                                !request.isForMainFrame &&
                                    request.url.scheme == "https" &&
                                    host.isNotBlank() &&
                                    !host.endsWith(".invalid")
                            )
                                return null
                            return HtmlPreviewDocument.denied()
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (document.contains(request.url)) return false
                            if (
                                request.isForMainFrame &&
                                    request.hasGesture() &&
                                    request.url.scheme in setOf("https", "http") &&
                                    request.url.host != document.host
                            ) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                }
                                    .onFailure { report("没有可打开此链接的浏览器。") }
                            }
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) report("网页加载失败，请重试。")
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            report("网页渲染已停止，请重新加载。")
                            return true
                        }
                    }
                loadUrl(document.url)
            }
        },
        onRelease = {
            it.stopLoading()
            it.webChromeClient = null
            it.webViewClient = WebViewClient()
            it.destroy()
        },
    )
}
