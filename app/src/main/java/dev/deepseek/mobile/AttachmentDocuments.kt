package dev.deepseek.mobile

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.FileNotFoundException

/**
 * Read-only SAF root containing attachment copies, never app credentials or arbitrary disk paths.
 */
class AttachmentDocuments : DocumentsProvider() {
    private val store
        get() = AttachmentStore(context!!)

    companion object {
        private const val ROOT_ID = "attachments"

        fun authority(context: Context) = "${context.packageName}.documents"

        fun rootUri(context: Context): Uri = DocumentsContract.buildRootsUri(authority(context))

        fun uri(context: Context, id: String): Uri =
            DocumentsContract.buildDocumentUri(authority(context), "f:$id")

        fun sessionUri(context: Context, sessionId: String): Uri {
            val tree = DocumentsContract.buildTreeDocumentUri(authority(context), "s:$sessionId")
            return DocumentsContract.buildDocumentUriUsingTree(tree, "s:$sessionId")
        }

        private val columns =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_FLAGS,
                Document.COLUMN_LAST_MODIFIED,
            )
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor =
            MatrixCursor(
                projection
                    ?: arrayOf(
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_DOCUMENT_ID,
                        Root.COLUMN_TITLE,
                        Root.COLUMN_SUMMARY,
                        Root.COLUMN_FLAGS,
                        Root.COLUMN_MIME_TYPES,
                    ),
            )
        val values =
            mapOf(
                Root.COLUMN_ROOT_ID to ROOT_ID,
                Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                Root.COLUMN_TITLE to "DSHA 会话文件",
                Root.COLUMN_SUMMARY to "已添加和生成的附件",
                Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_IS_CHILD,
                Root.COLUMN_MIME_TYPES to "*/*",
            )
        cursor.newRow().apply { cursor.columnNames.forEach { add(it, values[it]) } }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).apply { addDocument(this, documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: columns)
        val files = store.all()
        when {
            parentDocumentId == ROOT_ID ->
                files.map { it.sessionId }.distinct().forEach { addDocument(cursor, "s:$it") }
            parentDocumentId.startsWith("s:") &&
                AttachmentStore.valid(parentDocumentId.removePrefix("s:")) ->
                files
                    .filter { it.sessionId == parentDocumentId.removePrefix("s:") }
                    .sortedBy { it.name }
                    .forEach { addDocument(cursor, "f:${it.id}") }
            else -> throw FileNotFoundException("目录不存在")
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        runCatching {
            when {
                parentDocumentId == ROOT_ID ->
                    documentId.startsWith("s:") &&
                        AttachmentStore.valid(documentId.removePrefix("s:")) ||
                        documentId.startsWith("f:") &&
                            store.get(documentId.removePrefix("f:")).id.isNotEmpty()
                parentDocumentId.startsWith("s:") && documentId.startsWith("f:") ->
                    store.get(documentId.removePrefix("f:")).sessionId ==
                        parentDocumentId.removePrefix("s:")
                else -> false
            }
        }
        .getOrDefault(false)

    private fun addDocument(cursor: MatrixCursor, id: String) {
        val values =
            when {
                id == ROOT_ID ->
                    mapOf(
                        Document.COLUMN_DOCUMENT_ID to id,
                        Document.COLUMN_DISPLAY_NAME to "DSHA 会话文件",
                        Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
                        Document.COLUMN_FLAGS to 0,
                    )
                id.startsWith("s:") && AttachmentStore.valid(id.removePrefix("s:")) ->
                    mapOf(
                        Document.COLUMN_DOCUMENT_ID to id,
                        Document.COLUMN_DISPLAY_NAME to "会话 ${id.takeLast(8)}",
                        Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
                        Document.COLUMN_FLAGS to 0,
                    )
                id.startsWith("f:") -> {
                    val info = store.get(id.removePrefix("f:"))
                    mapOf(
                        Document.COLUMN_DOCUMENT_ID to id,
                        Document.COLUMN_DISPLAY_NAME to info.name,
                        Document.COLUMN_MIME_TYPE to info.mime,
                        Document.COLUMN_SIZE to info.size,
                        Document.COLUMN_FLAGS to 0,
                        Document.COLUMN_LAST_MODIFIED to store.file(info.id).lastModified(),
                    )
                }
                else -> throw FileNotFoundException("文件不存在")
            }
        cursor.newRow().apply { cursor.columnNames.forEach { add(it, values[it]) } }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r" || !documentId.startsWith("f:"))
            throw FileNotFoundException("会话文件仅可读取，请导出副本后编辑")
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(
            store.file(documentId.removePrefix("f:")),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    override fun getDocumentType(documentId: String): String =
        if (documentId.startsWith("f:")) store.get(documentId.removePrefix("f:")).mime
        else Document.MIME_TYPE_DIR
}
