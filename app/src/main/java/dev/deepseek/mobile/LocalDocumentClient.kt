package dev.deepseek.mobile

import android.content.Context
import android.webkit.*
import java.io.ByteArrayInputStream

/** Packaged document renderer only. Never reads file://, content:// or external URLs. */
internal class LocalDocumentClient(
    private val context: Context,
    private val folder: String,
    private val documents: Set<String>,
    private val fonts: Set<String> = emptySet(),
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse {
        fun denied() =
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Forbidden",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        val uri = request.url
        if (uri.scheme != "https" || uri.host != "document.dsha.invalid" || request.method != "GET")
            return denied()
        val path = uri.path.orEmpty()
        val name = path.substringAfterLast('/')
        val asset =
            when {
                path == "/assets/$name" && name in documents -> "$folder/$name"
                path == "/fonts/$name" && name in fonts -> "fonts/$name"
                else -> return denied()
            }
        return try {
            val mime =
                when {
                    name.endsWith(".js") -> "application/javascript"
                    name.endsWith(".css") -> "text/css"
                    name.endsWith(".ttf") -> "font/ttf"
                    name.endsWith(".woff2") -> "font/woff2"
                    else -> "text/html"
                }
            WebResourceResponse(
                mime,
                if (mime.startsWith("font/")) null else "UTF-8",
                200,
                "OK",
                mapOf(
                    "Content-Security-Policy" to
                        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
                    "X-Content-Type-Options" to "nosniff",
                ),
                context.assets.open(asset),
            )
        } catch (_: Exception) {
            denied()
        }
    }
}
