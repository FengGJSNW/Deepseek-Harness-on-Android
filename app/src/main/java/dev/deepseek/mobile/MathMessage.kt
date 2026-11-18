package dev.deepseek.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener

/** Only messages containing TeX use a WebView; ordinary reading stays native and lightweight. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun MathMessage(raw: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val density = LocalDensity.current
    val jobs = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(80) }
    val html by
        produceState("", raw) {
            value = withContext(Dispatchers.Default) { MathDocument.html(raw) }
        }
    val revision = FontSettings.revision
    val family =
        when (FontSettings.preset) {
            FontPreset.SYSTEM,
            FontPreset.OTHER -> "system-ui, sans-serif"
            FontPreset.DEFAULT,
            FontPreset.ANTHROPIC -> "DSHASerif, DSHACjkSerif, serif"
            FontPreset.MONO -> "DSHAMono, DSHACjk, monospace"
            else -> "DSHASans, DSHACjk, sans-serif"
        }
    AndroidView(
        factory = { c ->
            MathDocumentView(c).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                // FontEnvironment already applies system/app font scaling to CSS size.
                settings.textZoom = 100
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                isSaveEnabled = false
                webViewClient =
                    LocalDocumentClient(
                        c,
                        "math",
                        c.assets.list("math").orEmpty().toSet(),
                        setOf(
                            "source_sans.ttf",
                            "source_sans_italic.ttf",
                            "source_serif.ttf",
                            "source_serif_italic.ttf",
                            "jetbrains_mono.ttf",
                            "jetbrains_mono_italic.ttf",
                            "noto_sans_sc.ttf",
                            "noto_serif_sc.ttf",
                        ),
                    )
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun ready() {
                            jobs.launch(Dispatchers.Main.immediate) { ready = true }
                        }

                        @JavascriptInterface
                        fun height(value: Int) {
                            if (value in 1..50000)
                                jobs.launch(Dispatchers.Main.immediate) { height = value }
                        }

                        @JavascriptInterface
                        fun copy(text: String) {
                            if (text.length <= 65536)
                                jobs.launch(Dispatchers.Main.immediate) {
                                    (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager)
                                        .setPrimaryClip(
                                            android.content.ClipData.newPlainText("代码", text),
                                        )
                                }
                        }

                        @JavascriptInterface
                        fun open(url: String) {
                            if (MarkdownDocument.safeLink(url))
                                jobs.launch(Dispatchers.Main.immediate) {
                                    if (url.startsWith("dsha://file/"))
                                        FilePreviewState.open(url.substringAfterLast('/'))
                                    else runCatching { uri.openUri(url) }
                                }
                        }
                    },
                    "MathNative",
                )
                loadUrl("https://document.dsha.invalid/assets/math.html")
            }
        },
        update = { view ->
            if (ready && html.isNotBlank()) {
                val options =
                    JSONObject()
                        .put("html", html)
                        .put("size", 15f * density.fontScale)
                        .put("line", ExperienceSettings.lineSpacing)
                        .put("family", family)
                        .put("weight", FontSettings.weight)
                        .put("italic", FontSettings.italic)
                        .put("ligatures", FontSettings.ligatures)
                        .put("revision", revision)
                val payload = options.toString()
                // Height/layout recomposition must not rebuild DOM and reset code/table scroll.
                if (view.renderedPayload != payload) {
                    view.renderedPayload = payload
                    view.evaluateJavascript("window.renderDocument($payload)", null)
                }
            }
        },
        onRelease = {
            it.removeJavascriptInterface("MathNative")
            it.destroy()
        },
        modifier = modifier.fillMaxWidth().height(height.dp).testTag("math-message"),
    )
}

private class MathDocumentView(context: Context) : WebView(context) {
    var renderedPayload: String? = null

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        startActionMode(callback, ActionMode.TYPE_PRIMARY)

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        val copyMarkdown = 0x44534841
        val copyPlain = copyMarkdown + 1
        return super.startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    if (!callback.onCreateActionMode(mode, menu)) return false
                    augment(menu)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    val changed = callback.onPrepareActionMode(mode, menu)
                    return augment(menu) || changed
                }

                private fun augment(menu: Menu): Boolean {
                    val copy =
                        menu.findItem(android.R.id.copy)
                            ?: (0 until menu.size()).asSequence().map(menu::getItem).firstOrNull {
                                it.title == context.getText(android.R.string.copy) ||
                                    it.title == "复制文本"
                            }
                    if (copy != null) copy.title = "复制文本"
                    else if (menu.findItem(copyPlain) == null)
                        menu
                            .add(0, copyPlain, 0, "复制文本")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    if (menu.findItem(copyMarkdown) == null) {
                        menu
                            .add(0, copyMarkdown, (copy?.order ?: 0) + 1, "复制 Markdown")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                        return true
                    }
                    return false
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    if (item.itemId != copyMarkdown && item.itemId != copyPlain)
                        return callback.onActionItemClicked(mode, item)
                    val markdown = item.itemId == copyMarkdown
                    evaluateJavascript(
                        if (markdown) "window.selectedMarkdown()"
                        else "String(window.getSelection())",
                    ) { result ->
                        val text = runCatching {
                            JSONTokener(result).nextValue() as? String
                        }
                            .getOrNull()
                        if (!text.isNullOrBlank() && text.length <= 65536) {
                            context
                                .getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        if (markdown) "Markdown" else "文本",
                                        text,
                                    ),
                                )
                        }
                        mode.finish()
                    }
                    return true
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    if (callback is ActionMode.Callback2)
                        callback.onGetContentRect(mode, view, outRect)
                    else super.onGetContentRect(mode, view, outRect)
                }

                override fun onDestroyActionMode(mode: ActionMode) =
                    callback.onDestroyActionMode(mode)
            },
            type,
        )
    }
}
