package dev.deepseek.mobile

import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsRefinementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun typographyPreviewStaysPinnedAndSliderRequiresDeliberateThumbDrag() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme { FontEnvironment { SettingsScreen(onCreatePlugin = {}) {} } }
            }
        }
        ui.onNodeWithText("外观").performClick()
        ui.onNodeWithText("字体").performClick()
        val preview = ui.onNodeWithTag("typography-preview")
        val y = preview.fetchSemanticsNode().boundsInRoot.top
        val slider = ui.onNodeWithTag("typography-font-slider").performScrollTo()
        assertEquals(y, preview.fetchSemanticsNode().boundsInRoot.top, 1f)
        var initial = 0f
        ui.runOnUiThread { initial = FontSettings.scale }
        val track =
            ui.onNode(
                hasTestTag("parameter-slider") and
                    hasAnyAncestor(hasTestTag("typography-font-slider")),
            )
        track.performTouchInput { click(centerRight) }
        ui.runOnUiThread { assertEquals(initial, FontSettings.scale, .001f) }
        val inset = ui.activity.resources.displayMetrics.density * 16f
        track.performTouchInput {
            val thumb = inset + (width - 2 * inset) * ((initial - .85f) / .5f)
            down(Offset(thumb, centerY))
            moveTo(Offset(if (initial > 1.1f) inset else width - inset, centerY))
            up()
        }
        ui.runOnUiThread {
            assertTrue(kotlin.math.abs(initial - FontSettings.scale) > .08f)
            FontSettings.scale = initial
            FontSettings.changed()
        }
    }

    @Test
    fun completeConversationZipIsWrittenToDownloads() {
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
        val resolver = ui.activity.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun archives() = buildSet {
            resolver
                .query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    arrayOf("DSHA-backup-%.zip"),
                    null,
                )
                ?.use { cursor -> while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val existing = archives()
        lateinit var export: Job
        ui.runOnUiThread {
            AssistantState.current.messages.add(ChatEntry("user", "下载备份验证"))
            export = requireNotNull(AgentService.current).exportBackup(downloads = true)
        }
        runBlocking { withTimeout(20000) { export.join() } }
        val result = ContentUris.withAppendedId(collection, (archives() - existing).single())
        try {
            ZipInputStream(requireNotNull(resolver.openInputStream(result))).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "conversations-v3.json") {
                        assertTrue(zip.readBytes().decodeToString().contains("下载备份验证"))
                        found = true
                    }
                    assertFalse(entry.name.contains("keys", ignoreCase = true))
                    zip.closeEntry()
                }
                assertTrue(found)
            }
        } finally {
            resolver.delete(result, null, null)
        }
    }
}
