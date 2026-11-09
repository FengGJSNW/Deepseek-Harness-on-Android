package dev.deepseek.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AttachmentPreviewTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun prepare(): String {
        ui.waitUntil(30000) { AssistantState.ready }
        var id = ""
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            id = AssistantState.addSession().id
        }
        return id
    }

    @Test
    fun textLinkPreviewAndReadonlyProvider() {
        val session = prepare()
        val store = AttachmentStore(ui.activity)
        val file =
            store.save("# 你好鲸鲸\n这是会话里的文件预览。".byteInputStream(), session, "工作札记.md", "text/markdown")
        ui.runOnUiThread {
            AssistantState.current.messages.add(
                ChatEntry("assistant", "文件已保存：[工作札记](${file.link})", files = listOf(file.id)),
            )
        }
        ui.onNodeWithTag("file-card-${file.id}").performClick()
        ui.onNodeWithTag("workspace-document").assertIsDisplayed()
        try {
            ui.waitUntil(10000) {
                ui.onAllNodesWithText("# 你好鲸鲸\n这是会话里的文件预览。").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
            java.io
                .File(ui.activity.filesDir, "v072-file-preview-failure.txt")
                .writeText(
                    (0 until roots.fetchSemanticsNodes().size).joinToString("\n") {
                        roots[it].printToString()
                    },
                )
            TestSnapshots.capture(ui, "v072-file-preview-failure")
            throw error
        }
        TestSnapshots.capture(ui, "document-preview")
        ui.onNodeWithContentDescription("预览设置").performClick()
        ui.onNodeWithText("在文件管理器中查看").assertIsDisplayed()
        TestSnapshots.capture(ui, "document-options")
        ui.onNodeWithContentDescription("关闭面板").performClick()
        ui.onNodeWithContentDescription("返回会话").performClick()
        ui.onNodeWithTag("workspace-document").assertDoesNotExist()
        var layout: androidx.compose.ui.text.TextLayoutResult? = null
        val link = ui.onNodeWithText("文件已保存：工作札记")
        link.performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) { action ->
            val values = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action(values)
            layout = values.first()
        }
        link.performTouchInput { click(layout!!.getBoundingBox(6).center) }
        ui.waitUntil(5000) {
            ui.onNodeWithTag("workspace-document").isDisplayed()
        }
        ui.onNodeWithContentDescription("返回会话").performClick()
        ui.onNodeWithTag("workspace-document").assertDoesNotExist()
        val resolver = ui.activity.contentResolver
        val uri = AttachmentDocuments.uri(ui.activity, file.id)
        assertEquals(
            file.name,
            resolver
                .query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )!!
                .use {
                    it.moveToFirst()
                    it.getString(0)
                },
        )
        assertEquals(
            store.text(file.id),
            resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() },
        )
        assertTrue(runCatching { resolver.openFileDescriptor(uri, "rw")?.close() }.isFailure)
        assertTrue(runCatching { store.file("../shared_prefs") }.isFailure)
        val other =
            store.save(
                "other".byteInputStream(),
                UUID.randomUUID().toString(),
                "other.txt",
                "text/plain",
            )
        val provider =
            AttachmentDocuments().apply {
                attachInfo(
                    ui.activity,
                    android.content.pm.ProviderInfo().apply {
                        authority = AttachmentDocuments.authority(ui.activity)
                        exported = true
                        grantUriPermissions = true
                        readPermission = "android.permission.MANAGE_DOCUMENTS"
                        writePermission = "android.permission.MANAGE_DOCUMENTS"
                    },
                )
            }
        assertFalse(provider.isChildDocument("s:$session", "f:${other.id}"))
        assertTrue(provider.isChildDocument("s:$session", "f:${file.id}"))
        ui.runOnUiThread {
            AssistantState.persist()
            AssistantState.initialize(ui.activity)
            AssistantState.ready = true
        }
        assertEquals(file.id, AssistantState.current.messages.last().files.single())
    }

    @Test
    fun pdfPagesAndImagePreview() {
        val session = prepare()
        val store = AttachmentStore(ui.activity)
        val bytes = ByteArrayOutputStream()
        val document = PdfDocument()
        try {
            val pdf = document
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(480, 640, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText(
                    "DSHA - Preview ${index + 1}",
                    40f,
                    90f,
                    Paint().apply {
                        color = Color.BLUE
                        textSize = 28f
                        isAntiAlias = true
                    },
                )
                pdf.finishPage(page)
            }
            pdf.writeTo(bytes)
        } finally {
            document.close()
        }
        val pdf =
            store.save(bytes.toByteArray().inputStream(), session, "设计预览.pdf", "application/pdf")
        ui.runOnUiThread { FilePreviewState.open(pdf.id) }
        ui.waitUntil(10000) {
            ui.onAllNodesWithContentDescription("PDF 第 1 页").fetchSemanticsNodes().isNotEmpty()
        }
        TestSnapshots.capture(ui, "pdf-preview")
        ui.onNodeWithContentDescription("返回会话").performClick()
        ui.onNodeWithTag("workspace-document").assertDoesNotExist()
        val original = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        Canvas(original).drawColor(Color.rgb(83, 133, 231))
        val data =
            ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val image = store.save(data.toByteArray().inputStream(), session, "蓝色.png", "image/png")
        ui.runOnUiThread { FilePreviewState.open(image.id) }
        ui.onNodeWithTag("file-preview").assertIsDisplayed()
        ui.waitUntil(10000) {
            ui.onAllNodesWithTag("image-preview-canvas").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("image-preview-canvas").performTouchInput {
            pinch(
                androidx.compose.ui.geometry.Offset(centerX - 30, centerY),
                androidx.compose.ui.geometry.Offset(centerX + 30, centerY),
                androidx.compose.ui.geometry.Offset(centerX - 110, centerY),
                androidx.compose.ui.geometry.Offset(centerX + 110, centerY),
                400,
            )
        }
        val zoom =
            ui.onNodeWithTag("image-preview-canvas")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        assertNotEquals("缩放 100%", zoom)
        TestSnapshots.capture(ui, "image-preview")
        ui.onNodeWithContentDescription("关闭预览").performClick()
        ui.onNodeWithTag("file-preview").assertDoesNotExist()
    }

    @Test
    fun officeExtractionPreservesTextAndNeverEvaluatesContent() {
        val store = AttachmentStore(ui.activity)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("word/document.xml"))
            it.write(
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>鲸鲸的文档</w:t></w:r></w:p><w:p><w:r><w:t>第二段内容</w:t></w:r></w:p></w:body></w:document>"
                    .toByteArray(),
            )
            it.closeEntry()
        }
        val file =
            store.save(
                bytes.toByteArray().inputStream(),
                UUID.randomUUID().toString(),
                "文档.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        assertEquals("鲸鲸的文档\n第二段内容\n", store.text(file.id))
    }
}
