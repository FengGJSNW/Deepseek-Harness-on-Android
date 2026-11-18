package dev.deepseek.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerSearchIndexTest {
    @Test
    fun searchesMessagesAndCachedFilesByTypeWithoutResurrectingDeletedSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val owner = UUID.randomUUID().toString()
        val gone = UUID.randomUUID().toString()
        val store = AttachmentStore(context)
        val image =
            store.save(ByteArrayInputStream(byteArrayOf(1)), owner, "海边风景.jpg", "image/jpeg")
        val doc =
            store.save(
                ByteArrayInputStream("report".toByteArray()),
                owner,
                "发布计划.md",
                "text/markdown",
            )
        val deleted =
            store.save(ByteArrayInputStream(byteArrayOf(2)), gone, "已删除会话图片.jpg", "image/jpeg")
        val snapshot =
            DrawerSearchSnapshot(
                listOf(
                    DrawerSearchSession(
                        owner,
                        "项目讨论",
                        "p",
                        10,
                        listOf(ChatEntry("user", "修复键盘焦点")),
                        emptyList(),
                    ),
                ),
                listOf("p" to "手机项目"),
            )
        val index = loadDrawerSearchIndex(context, snapshot)
        assertEquals(
            owner,
            searchDrawerItems(index.items, "键盘焦点", DrawerSearchKind.CHATS).single().target,
        )
        assertEquals(
            image.id,
            searchDrawerItems(index.items, "海边", DrawerSearchKind.IMAGES).single().target,
        )
        assertEquals(
            doc.id,
            searchDrawerItems(index.items, "发布计划", DrawerSearchKind.FILES).single().target,
        )
        assertEquals(
            "p",
            searchDrawerItems(index.items, "手机项目", DrawerSearchKind.PROJECTS).single().target,
        )
        assertTrue(searchDrawerItems(index.items, "发布计划", DrawerSearchKind.IMAGES).isEmpty())
        assertFalse(index.items.any { it.target == deleted.id })
    }

    @Test
    fun legacyImageIndexIsReadOnlyAndOmitsMissingOrInvalidImagePaths() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val owner = UUID.randomUUID().toString()
        val image = UUID.randomUUID().toString()
        val file = File(context.filesDir, "screens/$image.jpg")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        val snapshot =
            DrawerSearchSnapshot(
                listOf(
                    DrawerSearchSession(
                        owner,
                        "旧照片",
                        "",
                        1,
                        listOf(ChatEntry("user", "", listOf(image, image, "../outside"))),
                        listOf(UUID.randomUUID().toString()),
                    ),
                ),
                emptyList(),
            )
        val images =
            loadDrawerSearchIndex(context, snapshot).items.filter {
                it.kind == DrawerSearchKind.IMAGES
            }
        assertEquals(1, images.size)
        assertEquals(image, images.single().target)
        assertTrue(images.single().legacyImage)
        assertFalse(File(context.filesDir, "attachments/$image").exists())
    }

    @Test
    fun officialSearchKeepsItsOwnNavigationTargets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val path = "/a/chat/s/12345678-abcd"
        val index =
            loadDrawerSearchIndex(
                context,
                DrawerSearchSnapshot(
                    emptyList(),
                    emptyList(),
                    listOf(OfficialSession(path, "Code Review")),
                ),
            )
        val result = searchDrawerItems(index.items, " code review ", DrawerSearchKind.ALL).single()
        assertTrue(result.official)
        assertEquals(path, result.target)
        assertTrue(searchDrawerItems(index.items, "", DrawerSearchKind.FILES).isEmpty())
    }
}
