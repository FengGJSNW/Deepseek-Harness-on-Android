package dev.deepseek.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.compose.runtime.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

internal class BrowserTab(
    val id: String,
    val owner: String,
    val sessionId: String,
    val view: WebView,
) {
    var title by mutableStateOf("网页")
    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
    var snapshot = ""
    var navigation: CompletableDeferred<Unit>? = null
}

internal object BrowserState {
    val tabs = mutableStateListOf<BrowserTab>()
}

/** No native JS bridge is installed in web pages; only constrained DOM actions are exposed. */
@SuppressLint("SetJavaScriptEnabled")
internal class AgentBrowser(private val context: Context) {
    private fun checkedUrl(url: String): String {
        val uri = Uri.parse(url)
        require(
            uri.scheme in setOf("https", "http") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null,
        ) {
            "浏览器仅允许有效 HTTP / HTTPS 地址"
        }
        return url
    }

    private fun owner(args: JSONObject) = args.optString("_agentId")

    private fun tab(args: JSONObject): BrowserTab {
        val task = AssistantState.running ?: error("任务已停止")
        return BrowserState.tabs.firstOrNull {
            it.id == args.getString("tab") &&
                it.owner == owner(args) &&
                it.sessionId == task.sessionId
        } ?: error("网页标签不属于当前智能体")
    }

    private suspend fun script(tab: BrowserTab, js: String): Any {
        val result = CompletableDeferred<Any>()
        tab.view.evaluateJavascript(js) { value ->
            runCatching { JSONTokener(value).nextValue() ?: JSONObject.NULL }
                .onSuccess { result.complete(it) }
                .onFailure { result.completeExceptionally(it) }
        }
        return withTimeout(15000) { result.await() }
    }

    suspend fun execute(method: String, args: JSONObject): JSONObject {
        if (method == "browser_open") {
            val url = checkedUrl(args.getString("url"))
            val task = AssistantState.running ?: error("任务已停止")
            require(BrowserState.tabs.count { it.sessionId == task.sessionId } < 16) {
                "当前会话已打开 16 个网页，请在功能区关闭不再使用的标签"
            }
            val view = WebView(context)
            val tab = BrowserTab(UUID.randomUUID().toString(), owner(args), task.sessionId, view)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val permissions =
                            dev.deepseek.mobile.harness.HarnessCoordinator.get(context).permissions
                        val policy =
                            dev.deepseek.mobile.harness.PermissionContext(
                                sessionId = task.sessionId,
                                projectId = task.projectId,
                            )
                        val allowed =
                            permissions
                                .evaluate(
                                    policy,
                                    dev.deepseek.mobile.harness.Capability.NETWORK,
                                    request.url.toString(),
                                )
                                .effect == dev.deepseek.mobile.harness.PermissionEffect.ALLOW
                        return if (allowed) null
                        else
                            WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                403,
                                "Forbidden",
                                emptyMap(),
                                java.io.ByteArrayInputStream("网络规则未允许此请求".toByteArray()),
                            )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = runCatching {
                        checkedUrl(request.url.toString())
                        false
                    }
                        .getOrDefault(true)

                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        tab.snapshot = ""
                        tab.loading = true
                        tab.url = url.orEmpty()
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        tab.loading = false
                        tab.url = url.orEmpty()
                        tab.title = view.title.orEmpty()
                        tab.navigation?.complete(Unit)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame)
                            tab.navigation?.completeExceptionally(
                                IllegalStateException("网页加载失败：${error.description}"),
                            )
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        BrowserState.tabs.remove(tab)
                        view.destroy()
                        tab.navigation?.completeExceptionally(
                            IllegalStateException("网页进程已退出，请重新打开"),
                        )
                        return true
                    }
                }
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    1080,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    1600,
                    android.view.View.MeasureSpec.EXACTLY,
                ),
            )
            view.layout(0, 0, 1080, 1600)
            BrowserState.tabs.add(tab)
            tab.navigation = CompletableDeferred()
            view.loadUrl(url)
            withTimeout(45000) { tab.navigation!!.await() }
            return JSONObject()
                .put("tab", tab.id)
                .put("title", tab.title)
                .put("url", tab.url)
                .put("message", "网页已在内置浏览器打开，请获取快照")
        }
        val tab = tab(args)
        if (method == "browser_source") {
            val offset = args.optInt("offset").coerceAtLeast(0)
            return (script(
                    tab,
                    """
                (() => { const html = document.documentElement.outerHTML;
                    return {url:location.href,title:document.title,kind:'rendered-dom',
                    html:html.slice($offset,$offset+65536),offset:$offset,total:html.length,
                    truncated:$offset+65536<html.length}; })()
            """
                        .trimIndent(),
                )
                    as? JSONObject ?: JSONObject())
                .put("tab", tab.id)
                .put("untrusted", true)
        }
        if (method == "browser_snapshot") {
            tab.snapshot = UUID.randomUUID().toString()
            val result =
                script(
                    tab,
                    """
                    (() => {
                        const visible = e => { const r=e.getBoundingClientRect(); const s=getComputedStyle(e); return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; };
                        const nodes = [...document.querySelectorAll('a,button,input,textarea,select,[role="button"],[contenteditable="true"]')].filter(visible).slice(0,300);
                        window.__dshaNodes = nodes;
                        return {title:document.title,url:location.href,text:document.body.innerText.slice(0,24000),nodes:nodes.map((e,node)=>({node,tag:e.tagName,type:e.type||'',label:(e.getAttribute('aria-label')||e.placeholder||e.innerText||e.name||'').slice(0,160),value:e.type==='password'?'[protected]':(e.value||'').slice(0,300)}))};
                    })()
                    """
                        .trimIndent(),
                )
            return (result as? JSONObject ?: JSONObject())
                .put("tab", tab.id)
                .put("snapshot", tab.snapshot)
                .put("untrusted", true)
        }
        require(args.getString("snapshot") == tab.snapshot && tab.snapshot.isNotBlank()) {
            "网页快照已失效，请重新读取"
        }
        val action = args.getString("action")
        val node = args.optInt("node", -1)
        val text = JSONObject.quote(args.optString("text").take(16000))
        val result =
            when (action) {
                "back" -> {
                    if (tab.view.canGoBack()) tab.view.goBack()
                    JSONObject().put("ok", true)
                }
                "scroll" ->
                    script(tab, "(() => { scrollBy(0, innerHeight*.75); return {ok:true}; })()")
                "click",
                "fill" ->
                    script(
                        tab,
                        """
                (() => {
                    const e=window.__dshaNodes?.[$node];
                    if(!e || !e.isConnected || e.getBoundingClientRect().width===0) return {error:'元素已变化，请重新获取快照'};
                    if(e.type==='password') return {error:'请由用户在浏览器内手动输入密码'};
                    if(${JSONObject.quote(action)}==='click') e.click();
                    else {
                        if(!e.matches('input,textarea,[contenteditable="true"]')) return {error:'元素不可输入'};
                        e.focus();
                        if(e.isContentEditable) e.textContent=$text;
                        else { const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto,'value').set.call(e,$text); }
                        e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                    }
                    return {ok:true};
                })()
            """
                            .trimIndent(),
                    )
                else -> error("不支持此网页操作")
            }
        tab.snapshot = ""
        return (result as? JSONObject ?: JSONObject()).put("message", "网页操作已提交，请获取新快照验证")
    }

    fun close() {
        BrowserState.tabs.forEach {
            (it.view.parent as? android.view.ViewGroup)?.removeView(it.view)
            it.view.destroy()
        }
        BrowserState.tabs.clear()
    }
}
