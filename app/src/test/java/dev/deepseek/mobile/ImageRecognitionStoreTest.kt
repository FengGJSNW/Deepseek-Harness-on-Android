package dev.deepseek.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImageRecognitionStoreTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Application>()

    private fun fixture(): Pair<PhoneTask, String> {
        val owner = UUID.randomUUID().toString()
        val image =
            AttachmentStore(context)
                .save(ByteArrayInputStream(byteArrayOf(1)), owner, "IMG_0001.jpg", "image/jpeg")
        File(context.filesDir, "screens/${image.id}.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        return PhoneTask(
            sessionId = owner,
            text = "",
            images = listOf(image.id),
            model = "deepseek-flash",
            effort = "low",
            access = "none",
            phoneTools = false,
            fileTools = false,
            workspace = "",
        ) to image.id
    }

    private fun pending(task: PhoneTask, id: String) =
        JSONObject()
            .put("action", "pending")
            .put("taskId", task.id)
            .put("ids", JSONArray(listOf(id)))

    private fun save(task: PhoneTask, item: JSONObject, text: String) =
        JSONObject()
            .put("action", "save")
            .put("taskId", task.id)
            .put("entries", JSONArray().put(JSONObject(item.toString()).put("text", text)))

    @Test
    fun recognizedTextSurvivesReopeningAndFindsItsOriginalImage() = runBlocking {
        val (task, id) = fixture()
        val cache = ImageRecognitionStore(context)
        val item =
            cache.exchange(task, pending(task, id)) {}.getJSONArray("pending").getJSONObject(0)
        cache.exchange(task, save(task, item, "蓝色公交站牌\n人民路 48 路")) {}
        val reopened = ImageRecognitionStore(context)
        assertTrue(reopened.text(id, task.sessionId).contains("人民路"))
        assertEquals(
            0,
            reopened.exchange(task, pending(task, id)) {}.getJSONArray("pending").length(),
        )
        val snapshot =
            DrawerSearchSnapshot(
                listOf(DrawerSearchSession(task.sessionId, "图片会话", "", 1, emptyList(), listOf(id))),
                emptyList(),
            )
        val result =
            searchDrawerItems(
                    loadDrawerSearchIndex(context, snapshot).items,
                    "人民路",
                    DrawerSearchKind.IMAGES,
                )
                .single()
        assertEquals(id, result.target)
        assertTrue(result.recognition.contains("48 路"))
        assertEquals("", reopened.text(id, UUID.randomUUID().toString()))
    }

    @Test
    fun rejectsForeignImageIdsAndStaleContentBeforeWriting() {
        val (task, id) = fixture()
        val cache = ImageRecognitionStore(context)
        val item =
            cache.exchange(task, pending(task, id)) {}.getJSONArray("pending").getJSONObject(0)
        assertThrows(IllegalArgumentException::class.java) {
            cache.exchange(task, pending(task, UUID.randomUUID().toString())) {}
        }
        File(context.filesDir, "screens/$id.jpg").writeBytes(byteArrayOf(2))
        assertThrows(IllegalStateException::class.java) {
            cache.exchange(task, save(task, item, "旧识别")) {}
        }
        assertEquals("", cache.text(id, task.sessionId))
        val current =
            cache.exchange(task, pending(task, id)) {}.getJSONArray("pending").getJSONObject(0)
        assertNotEquals(item.getString("sha256"), current.getString("sha256"))
        assertThrows(IllegalArgumentException::class.java) {
            cache.exchange(task, save(task, current, "越权").put("_agentId", "child")) {}
        }
    }

    @Test
    fun cancellationDoesNotReplaceExistingRecognition() {
        val (task, id) = fixture()
        val cache = ImageRecognitionStore(context)
        val item =
            cache.exchange(task, pending(task, id)) {}.getJSONArray("pending").getJSONObject(0)
        cache.exchange(task, save(task, item, "已完成的识别")) {}
        assertThrows(CancellationException::class.java) {
            cache.exchange(task, save(task, item, "已停止的识别")) {
                throw CancellationException("Stopped")
            }
        }
        assertEquals("已完成的识别", cache.text(id, task.sessionId))
    }
}
