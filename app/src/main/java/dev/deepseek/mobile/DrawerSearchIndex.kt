package dev.deepseek.mobile

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

internal enum class DrawerSearchKind(val label: String) {
    ALL("全部"),
    CHATS("会话"),
    IMAGES("图片"),
    FILES("文件"),
    PROJECTS("项目"),
}

internal data class DrawerSearchSession(
    val id: String,
    val title: String,
    val projectId: String,
    val updatedAt: Long,
    val messages: List<ChatEntry>,
    val images: List<String>,
    val files: List<String> = emptyList(),
)

internal data class DrawerSearchSnapshot(
    val sessions: List<DrawerSearchSession>,
    val projects: List<Pair<String, String>>,
    val official: List<OfficialSession> = emptyList(),
    val imageRevision: Long = 0,
)

internal data class DrawerSearchItem(
    val id: String,
    val kind: DrawerSearchKind,
    val title: String,
    val detail: String,
    val target: String,
    val updatedAt: Long = 0,
    val sessionId: String = "",
    val terms: List<String> = emptyList(),
    val legacyImage: Boolean = false,
    val official: Boolean = false,
    val recognition: String = "",
) {
    fun matches(query: String): Boolean =
        query.isBlank() ||
            title.contains(query, true) ||
            recognition.contains(query, true) ||
            detail.contains(query, true) ||
            terms.any { it.contains(query, true) }
}

internal data class DrawerSearchIndex(
    val items: List<DrawerSearchItem> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
)

/** Snapshot Compose state on Main; metadata and disk access stay on IO and never upload files. */
@Composable
internal fun rememberDrawerSearchIndex(open: Boolean, work: Boolean): State<DrawerSearchIndex> {
    val context = LocalContext.current.applicationContext
    val imageRevision by ImageRecognitionStore.revision.collectAsState()
    return produceState(DrawerSearchIndex(), open, work) {
        if (!open) {
            value = DrawerSearchIndex()
            return@produceState
        }
        snapshotFlow {
            if (work)
                DrawerSearchSnapshot(
                    AssistantState.sessions.map { session ->
                        DrawerSearchSession(
                            session.id,
                            session.title,
                            session.projectId,
                            session.updatedAt,
                            session.messages.toList(),
                            session.images.toList(),
                            session.files.toList(),
                        )
                    },
                    AssistantState.projects.map { it.id to it.title },
                    imageRevision = imageRevision,
                )
            else
                DrawerSearchSnapshot(
                    emptyList(),
                    emptyList(),
                    OfficialChatNavigation.snapshot.sessions,
                )
        }
            .collectLatest { snapshot ->
                value = DrawerSearchIndex(loading = true)
                value =
                    try {
                        withContext(Dispatchers.IO) { loadDrawerSearchIndex(context, snapshot) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        DrawerSearchIndex(error = "暂时无法读取本地搜索内容，请关闭搜索后重试")
                    }
            }
    }
}

internal suspend fun loadDrawerSearchIndex(
    context: Context,
    snapshot: DrawerSearchSnapshot,
): DrawerSearchIndex {
    val records = mutableListOf<DrawerSearchItem>()
    val sessions = snapshot.sessions.associateBy { it.id }
    val projects = snapshot.projects.toMap()
    snapshot.sessions.forEach { session ->
        currentCoroutineContext().ensureActive()
        records +=
            DrawerSearchItem(
                "chat:${session.id}",
                DrawerSearchKind.CHATS,
                session.title,
                projects[session.projectId] ?: "独立会话",
                session.id,
                session.updatedAt,
                session.id,
                session.messages.map { it.text },
            )
    }
    snapshot.projects.forEach { (id, title) ->
        val children = snapshot.sessions.filter { it.projectId == id }
        records +=
            DrawerSearchItem(
                "project:$id",
                DrawerSearchKind.PROJECTS,
                title,
                "${children.size} 个会话",
                id,
                children.maxOfOrNull { it.updatedAt } ?: 0,
            )
    }
    snapshot.official.forEachIndexed { index, session ->
        records +=
            DrawerSearchItem(
                "official:${session.path}",
                DrawerSearchKind.CHATS,
                session.title,
                "官方会话",
                session.path,
                (snapshot.official.size - index).toLong(),
                official = true,
            )
    }
    if (sessions.isEmpty()) return DrawerSearchIndex(records)
    val store = AttachmentStore(context)
    val recognition = ImageRecognitionStore(context)
    val indexedImages = mutableSetOf<String>()
    store.all().forEach { file ->
        currentCoroutineContext().ensureActive()
        // Cached copies belonging to a deleted conversation do not resurrect that conversation.
        val owner = sessions[file.sessionId] ?: return@forEach
        val content = runCatching { store.file(file.id) }.getOrNull() ?: return@forEach
        val image = file.mime.startsWith("image/")
        if (image) indexedImages += file.id
        records +=
            DrawerSearchItem(
                "file:${file.id}",
                if (image) DrawerSearchKind.IMAGES else DrawerSearchKind.FILES,
                file.name,
                "${owner.title} · ${formatFileSize(file.size)}",
                file.id,
                content.lastModified(),
                owner.id,
                listOf(projects[owner.projectId].orEmpty(), file.mime),
                recognition = if (image) recognition.text(file.id, owner.id) else "",
            )
    }
    snapshot.sessions.forEach { owner ->
        val images = owner.images + owner.messages.flatMap { it.images }
        images.distinct().forEach image@{ id ->
            currentCoroutineContext().ensureActive()
            if (id in indexedImages || !AttachmentStore.valid(id)) return@image
            val file = File(context.filesDir, "screens/$id.jpg")
            if (!file.isFile || file.canonicalFile != file.absoluteFile) return@image
            indexedImages += id
            records +=
                DrawerSearchItem(
                    "file:$id",
                    DrawerSearchKind.IMAGES,
                    "图片.jpg",
                    owner.title,
                    id,
                    file.lastModified(),
                    owner.id,
                    listOf(projects[owner.projectId].orEmpty()),
                    legacyImage = true,
                    recognition = recognition.text(id, owner.id),
                )
        }
    }
    return DrawerSearchIndex(records.sortedByDescending { it.updatedAt })
}

internal fun searchDrawerItems(
    items: List<DrawerSearchItem>,
    query: String,
    kind: DrawerSearchKind,
): List<DrawerSearchItem> {
    val term = query.trim()
    return items.filter { (kind == DrawerSearchKind.ALL || it.kind == kind) && it.matches(term) }
}
