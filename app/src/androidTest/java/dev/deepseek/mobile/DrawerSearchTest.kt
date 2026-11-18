package dev.deepseek.mobile

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Fixture writes and fake credentials are restricted to a dedicated emulator. */
class DrawerSearchTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
    }

    private fun awaitResult(key: String) {
        ui.waitUntil(10000) {
            ui.onAllNodesWithTag("drawer-search-result-$key").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test
    fun fullWidthSearchKeepsKeyboardAndOpensConversationImageAndDocumentTargets() {
        emulatorOnly()
        val state = AssistantState
        val owner = UUID.randomUUID().toString()
        val store = AttachmentStore(ui.activity)
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                }
                .toByteArray()
        val image = store.save(ByteArrayInputStream(bytes), owner, "预选项回归图片.png", "image/png")
        val document =
            store.save(
                ByteArrayInputStream("document".toByteArray()),
                owner,
                "预选项回归文档.txt",
                "text/plain",
            )
        val conversation =
            Conversation(owner, "预选项回归会话").apply {
                messages.add(ChatEntry("user", "只能通过正文找到", files = listOf(document.id)))
            }
        val previousMode = state.appMode
        val previousSelected = state.selectedId
        var selections = 0
        ui.runOnUiThread {
            state.sessions.add(conversation)
            state.appMode = "work"
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        val controller = remember { DrawerSearchController() }
                        val growth = rememberDrawerSearchProgress(controller)
                        CompositionLocalProvider(LocalDrawerSearch provides controller) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                Box(
                                    Modifier.fillMaxWidth(.82f)
                                        .fillMaxHeight()
                                        .searchBackgroundBlur(growth)
                                        .inactiveIf(controller.visible),
                                ) {
                                    SessionDrawer({ selections++ }, {}, {}, {})
                                }
                                DrawerSearchOverlay(controller, growth)
                            }
                        }
                    }
                }
            }
        }
        try {
            ui.onNodeWithContentDescription("搜索内容").performClick()
            ui.waitUntil(10000) { keyboardVisible() }
            ui.onNodeWithTag("drawer-search-overlay").assertIsDisplayed()
            ui.onNodeWithTag("drawer-mode").assertDoesNotExist()
            ui.onNodeWithTag("drawer-search-field").performTextInput("预选项回归")
            ui.onNodeWithTag("drawer-search-field").assertIsFocused().assertTextContains("预选项回归")
            assertTrue(keyboardVisible())
            awaitResult("file:${image.id}")
            // Recognition completes while search is open: the image becomes a candidate
            // immediately.
            ui.onNodeWithTag("drawer-search-field").performTextReplacement("停车场出口 C7")
            val recognitionTask =
                PhoneTask(
                    sessionId = owner,
                    text = "",
                    images = listOf(image.id),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "none",
                    phoneTools = false,
                    fileTools = false,
                    workspace = "",
                )
            File(ui.activity.filesDir, "screens/${image.id}.jpg").apply {
                parentFile!!.mkdirs()
                writeBytes(bytes)
            }
            val cache = ImageRecognitionStore(ui.activity)
            val entry =
                cache
                    .exchange(
                        recognitionTask,
                        JSONObject()
                            .put("taskId", recognitionTask.id)
                            .put("action", "pending")
                            .put("ids", JSONArray(listOf(image.id))),
                    ) {}
                    .getJSONArray("pending")
                    .getJSONObject(0)
                    .put("text", "蓝色路牌，停车场出口 C7")
            cache.exchange(
                recognitionTask,
                JSONObject()
                    .put("taskId", recognitionTask.id)
                    .put("action", "save")
                    .put("entries", JSONArray().put(entry)),
            ) {}
            awaitResult("file:${image.id}")
            ui.onNodeWithTag("drawer-search-result-file:${image.id}").performClick()
            ui.waitUntil(10000) { FilePreviewState.requested == image.id }
            assertEquals(owner, state.selectedId)
            ui.runOnUiThread { FilePreviewState.clear() }

            ui.onNodeWithContentDescription("搜索内容").performClick()
            ui.onNodeWithTag("drawer-search-field")
                .assert(
                    SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                        androidx.compose.ui.text.AnnotatedString(""),
                    ),
                )
            ui.onNodeWithTag("drawer-search-field").performTextInput("预选项回归文档")
            awaitResult("file:${document.id}")
            ui.onNodeWithTag("drawer-search-result-file:${document.id}").performClick()
            ui.waitUntil(10000) { FilePreviewState.requested == document.id }
            ui.runOnUiThread { FilePreviewState.clear() }

            ui.onNodeWithContentDescription("搜索内容").performClick()
            ui.onNodeWithTag("drawer-search-field").performTextInput("只能通过正文找到")
            awaitResult("chat:$owner")
            ui.onNodeWithTag("drawer-search-result-chat:$owner").performClick()
            assertEquals(3, selections)
            assertEquals(owner, state.selectedId)
        } finally {
            ui.runOnUiThread {
                FilePreviewState.clear()
                state.sessions.removeAll { it.id == owner }
                state.selectedId = previousSelected
                state.appMode = previousMode
                state.persist()
            }
        }
    }

    @Test
    fun actualImageOnlySendQueuesBothImagesAndNeverAddsAnInventedUserMessage() {
        emulatorOnly()
        val state = AssistantState
        val keys = KeyStore(ui.activity)
        val oldCredential = keys.list().firstOrNull { it.selected }?.id
        val fakeCredential =
            keys.save("sk-dsha-emulator-fixture-not-a-real-key", "UI regression fixture")
        val oldSelected = state.selectedId
        val oldActive = state.activeTaskId
        val owner = Conversation().apply { titleGenerationAttempted = true }
        val photos = List(2) { UUID.randomUUID().toString() }
        val guard =
            PhoneTask(
                sessionId = owner.id,
                text = "fixture queue guard",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "none",
                phoneTools = false,
                fileTools = false,
                workspace = "",
            )
        try {
            ui.runOnUiThread {
                state.sessions.add(owner)
                state.selectedId = owner.id
                state.tasks.add(guard)
                state.activeTaskId = guard.id
                owner.images.addAll(photos)
                assertTrue(
                    state.busy,
                ) // Keep the actual send path queued; no model request is made.
                assertEquals(AgentService.SendResult.QUEUED, AgentService.current!!.send(""))
                val user = owner.messages.single()
                assertEquals("user", user.type)
                assertEquals("", user.text)
                assertEquals(photos, user.images)
                val task = state.tasks.single { it.sessionId == owner.id && it.id != guard.id }
                assertEquals("", task.text)
                assertEquals("", task.json().getString("text"))
                assertEquals(photos, task.images)
                assertEquals("图片会话", owner.title)
                assertTrue(owner.images.isEmpty())
            }
        } finally {
            ui.runOnUiThread {
                state.tasks.removeAll { it.sessionId == owner.id }
                state.sessions.removeAll { it.id == owner.id }
                state.activeTaskId = oldActive
                state.selectedId = oldSelected
                keys.delete(fakeCredential)
                if (oldCredential != null) keys.select(oldCredential)
                state.configured = keys.configured()
                state.persist()
            }
        }
    }
}
