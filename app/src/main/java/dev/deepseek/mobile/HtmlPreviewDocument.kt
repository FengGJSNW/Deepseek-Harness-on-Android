package dev.deepseek.mobile

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

internal val LocalAttachment.isHtml: Boolean
    get() =
        mime.substringBefore(';').lowercase() in setOf("text/html", "application/xhtml+xml") ||
            name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "xhtml")

/** Only published copies from this conversation are reachable, never workspace/private paths. */
internal class HtmlPreviewDocument(
    private val store: AttachmentStore,
    val entry: LocalAttachment,
    attachments: List<LocalAttachment>,
) {
    val host = "preview-${entry.id}.dsha.invalid"
    val url = "https://$host/" + Uri.encode(entry.name)
    private val directory = entry.sourcePath.substringBeforeLast('/', "")
    private val files =
        attachments
            .filter { it.sessionId == entry.sessionId }
            .mapNotNull { file ->
                val path =
                    if (directory.isNotEmpty()) {
                        if (!file.sourcePath.startsWith("$directory/")) return@mapNotNull null
                        file.sourcePath.removePrefix("$directory/")
                    } else {
                        if (file.sourcePath.isNotEmpty()) return@mapNotNull null
                        file.name
                    }
                if (!validPath(path)) null else path to file
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() } + (entry.name to entry)

    fun contains(uri: Uri): Boolean = localPath(uri)?.let { it in files } == true

    fun response(uri: Uri, method: String = "GET"): WebResourceResponse {
        if (method != "GET") return denied()
        val file = localPath(uri)?.let(files::get) ?: return denied()
        return try {
            WebResourceResponse(
                if (file.isHtml) "text/html" else resourceMime(file),
                if (
                    file.isHtml ||
                        resourceMime(file).startsWith("text/") ||
                        resourceMime(file) in setOf("application/javascript", "application/json")
                )
                    "UTF-8"
                else null,
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "no-store",
                    "Referrer-Policy" to "no-referrer",
                    "X-Content-Type-Options" to "nosniff",
                    "Content-Security-Policy" to
                        "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https:; " +
                            "style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; " +
                            "font-src 'self' data: https:; connect-src 'self' https:; " +
                            "media-src 'self' data: blob: https:; base-uri 'self'; form-action 'none'",
                ),
                store.file(file.id).inputStream(),
            )
        } catch (_: Exception) {
            denied()
        }
    }

    private fun localPath(uri: Uri): String? {
        if (uri.scheme != "https" || uri.host != host || uri.port != -1 || uri.userInfo != null)
            return null
        return uri.path?.removePrefix("/")?.takeIf(::validPath)
    }

    companion object {
        private fun validPath(path: String) =
            path.isNotEmpty() &&
                !path.contains('\\') &&
                path.none { it.isISOControl() } &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        private fun resourceMime(file: LocalAttachment) =
            when (file.name.substringAfterLast('.', "").lowercase()) {
                "js",
                "mjs" -> "application/javascript"
                "css" -> "text/css"
                "svg" -> "image/svg+xml"
                "json" -> "application/json"
                else -> file.mime.substringBefore(';')
            }

        fun denied() =
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
    }
}
