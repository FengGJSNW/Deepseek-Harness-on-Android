package dev.deepseek.mobile

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HtmlPreviewDocumentTest {
    private val store = AttachmentStore(ApplicationProvider.getApplicationContext())

    @Test
    fun resourcesKeepRelativePathsAndCannotReadAnotherConversationOrPrivatePath() {
        val session = UUID.randomUUID().toString()
        val siblings = mutableListOf<LocalAttachment>()
        fun save(name: String, source: String, sessionId: String = session): LocalAttachment {
            val file = store.save(name.byteInputStream(), sessionId, name, "text/plain")
            // Source persistence is covered on Android; JVM policy fixtures need no AtomicFile
            // rename.
            return file.copy(sourcePath = source, sourceTask = "task").also { siblings.add(it) }
        }
        val html = save("页面.html", "runtime/workspace/site/页面.html")
        val css = save("app.css", "runtime/workspace/site/css/app.css")
        save("secret.txt", "runtime/workspace/private/secret.txt")
        save("foreign.js", "runtime/workspace/site/foreign.js", UUID.randomUUID().toString())
        val document = HtmlPreviewDocument(store, html, siblings)
        assertTrue(html.isHtml)
        assertEquals("页面.html", document.response(Uri.parse(document.url)).data.reader().readText())
        val prefix = "https://${document.host}/"
        val target = Uri.parse(prefix + "css/app.css?v=2")
        assertTrue(
            "${html.sourcePath}: ${css.sourcePath}: $target / ${target.path}",
            document.contains(target),
        )
        val response = document.response(target)
        assertEquals(200, response.statusCode)
        assertEquals("text/css", response.mimeType)
        assertEquals(css.name, response.data.reader().readText())
        listOf(
                prefix + "foreign.js",
                prefix + "../private/secret.txt",
                prefix + "%2e%2e/private/secret.txt",
                prefix + "..%5csecret.txt",
                "file:///data/data/dev.deepseek.mobile/shared_prefs/settings.xml",
                "content://dev.deepseek.mobile.documents/anything",
                "https://document.dsha.invalid/assets/index.html",
            )
            .forEach { assertEquals(it, 404, document.response(Uri.parse(it)).statusCode) }
        assertEquals(404, document.response(Uri.parse(document.url), "POST").statusCode)
    }

    @Test
    fun ambiguousResourceNamesAreNotSilentlyResolvedToAnotherFile() {
        val session = UUID.randomUUID().toString()
        val html = store.save("html".byteInputStream(), session, "index.htm", "text/html")
        repeat(2) { store.save("css".byteInputStream(), session, "app.css", "text/css") }
        val document = HtmlPreviewDocument(store, html, store.all())
        assertEquals(
            404,
            document.response(Uri.parse("https://${document.host}/app.css")).statusCode,
        )
        assertEquals(200, document.response(Uri.parse(document.url)).statusCode)
    }
}
