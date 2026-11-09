package dev.deepseek.mobile

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.widget.ImageView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object FilePreviewState {
    var requested by mutableStateOf("")
        private set

    fun open(id: String) {
        if (AttachmentStore.valid(id)) requested = id
    }

    fun clear() {
        requested = ""
    }
}

@Composable
internal fun AttachmentCard(
    id: String,
    upload: Boolean = false,
    removable: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    val store = remember(context) { AttachmentStore(context) }
    val info by
        produceState<LocalAttachment?>(null, id) {
            value = withContext(Dispatchers.IO) { runCatching { store.get(id) }.getOrNull() }
        }
    val file = info ?: return
    val isUpload = upload || removable != null
    val export =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(file.mime),
        ) { uri ->
            if (uri != null)
                jobs.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                store.file(id).inputStream().use { it.copyTo(out) }
                            } ?: error("无法写入所选位置")
                        }
                    }
                        .onSuccess {
                            android.widget.Toast.makeText(
                                    context,
                                    "文件已保存",
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                        .onFailure {
                            android.widget.Toast.makeText(
                                    context,
                                    "保存失败：${it.message}",
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                }
        }
    Surface(
        onClick = { FilePreviewState.open(id) },
        color = if (isUpload) Color.White else Color(0xFFFAF9F6),
        shape = ContinuousShape(12.dp),
        border = BorderStroke(.8.dp, if (isUpload) ReadingStyle.workLine else ReadingStyle.line),
        modifier = Modifier.widthIn(max = 330.dp).testTag("file-card-$id"),
    ) {
        Column {
            Row(
                Modifier.padding(
                    start = 13.dp,
                    end = if (removable == null) 11.dp else 3.dp,
                    top = 13.dp,
                    bottom = 13.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp)
                        .background(
                            if (isUpload) Color(0xFFF2F2F2) else Color(0xFFEDEBE5),
                            ContinuousShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (file.mime.startsWith("image/")) AppIcons.Image
                        else AppIcons.Description,
                        null,
                        Modifier.size(20.dp),
                        tint = if (isUpload) WhaleMuted else Color(0xFF82786A),
                    )
                }
                Column(Modifier.weight(1f, false).padding(start = 11.dp)) {
                    Text(
                        file.name,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${file.name.substringAfterLast('.',"文件").uppercase()} · ${formatFileSize(file.size)}",
                        fontSize = 10.sp,
                        color = WhaleMuted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (removable != null)
                    IconButton(onClick = removable, modifier = Modifier.size(32.dp)) {
                        Icon(AppIcons.Close, "移除文件附件", Modifier.size(15.dp), tint = WhaleMuted)
                    }
            }
            if (!isUpload) {
                HorizontalDivider(color = ReadingStyle.line, thickness = .7.dp)
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "预览文件",
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                        color = ReadingStyle.secondary,
                    )
                    IconButton(
                        onClick = { export.launch(file.name) },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            AppIcons.Download,
                            "下载 ${file.name}",
                            Modifier.size(16.dp),
                            tint = ReadingStyle.secondary,
                        )
                    }
                }
            }
        }
    }
}

internal fun formatFileSize(size: Long) =
    when {
        size >= 1048576 -> "%.1f MB".format(size / 1048576f)
        size >= 1024 -> "%.1f KB".format(size / 1024f)
        else -> "$size B"
    }

private val LocalAttachment.isPreviewImage: Boolean
    get() = mime.startsWith("image/") && mime != "image/svg+xml"

@Composable
internal fun FilePreview() {
    val request = FilePreviewState.requested
    val sessionId = AssistantState.selectedId
    val context = LocalContext.current
    var image by remember(sessionId) { mutableStateOf<LocalAttachment?>(null) }
    LaunchedEffect(request, sessionId) {
        if (request.isBlank()) return@LaunchedEffect
        val file =
            withContext(Dispatchers.IO) {
                runCatching { AttachmentStore(context).get(request) }.getOrNull()
            }
        FilePreviewState.clear()
        when {
            file == null -> AssistantState.error = "文件已不可用"
            file.isPreviewImage -> image = file
            else -> {
                image = null
                WorkspaceState.open(WorkspaceTab.Document(file))
            }
        }
    }
    image?.let { file ->
        CenterIsland({ image = null }) {
            AttachmentPreview(
                file,
                Modifier.fillMaxWidth()
                    .height(
                        (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp *
                                .72f)
                            .dp,
                    )
                    .testTag("file-preview"),
                closeImage = { image = null },
            )
        }
    }
}

@Composable
internal fun DocumentWorkspace(file: LocalAttachment) {
    AttachmentPreview(file, Modifier.fillMaxSize().testTag("workspace-document"))
}

@Composable
internal fun WorkspaceFiles(sessionId: String) {
    val context = LocalContext.current
    val files by
        produceState<List<LocalAttachment>?>(null, sessionId) {
            value =
                withContext(Dispatchers.IO) {
                    AttachmentStore(context)
                        .all()
                        .filter { it.sessionId == sessionId }
                        .sortedByDescending {
                            runCatching { AttachmentStore(context).file(it.id).lastModified() }
                                .getOrDefault(0L)
                        }
                }
        }
    Column(Modifier.padding(horizontal = 18.dp)) {
        Text("会话文件", Modifier.padding(vertical = 14.dp), fontSize = 17.sp)
        when {
            files == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            files.orEmpty().isEmpty() ->
                Text("本会话的附件和 Agent 发布的文件会显示在这里。", color = WhaleMuted, fontSize = 13.sp)
            else ->
                LazyColumn {
                    items(files.orEmpty(), key = { it.id }) { file ->
                        SettingsLink(
                            if (file.isPreviewImage) AppIcons.Image else AppIcons.Description,
                            file.name,
                            formatFileSize(file.size),
                        ) {
                            FilePreviewState.open(file.id)
                        }
                    }
                }
        }
    }
}

@Composable
private fun AttachmentPreview(
    file: LocalAttachment,
    modifier: Modifier,
    closeImage: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val id = file.id
    val store = remember(context) { AttachmentStore(context) }
    var settings by remember(id) { mutableStateOf(false) }
    val content by
        produceState<Result<File>?>(null, id) {
            value = withContext(Dispatchers.IO) { runCatching { store.file(id) } }
        }
    Surface(modifier, color = Color(0xFFFAF9F6)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .height(54.dp)
                    .background(Color.White)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (closeImage != null) SmallIcon(AppIcons.ArrowBack, "关闭预览", action = closeImage)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        file.name,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(formatFileSize(file.size), fontSize = 10.sp, color = WhaleMuted)
                }
                val change =
                    ChangeState.changes.lastOrNull {
                        it.taskId == file.sourceTask && it.path == file.sourcePath
                    }
                if (change != null)
                    SmallIcon(AppIcons.Diff, "查看文件改动") { ChangeState.selected = change }
                SmallIcon(AppIcons.Settings, "预览设置") { settings = true }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val result = content
                val data = result?.getOrNull()
                when {
                    result == null ->
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center).size(26.dp),
                            strokeWidth = 2.dp,
                        )
                    data == null ->
                        Text("文件已不可用", Modifier.align(Alignment.Center), color = WhaleMuted)
                    file.isPreviewImage -> ImagePreview(data)
                    file.mime == "application/pdf" -> PdfPreview(data)
                    else -> TextDocumentPreview(store, file)
                }
            }
        }
    }
    if (settings) {
        WhaleSheet("文件选项", file.name, { settings = false }) {
            fun launch(intent: Intent) {
                runCatching { context.startActivity(intent) }
                    .onFailure { AgentService.current?.toast("没有可处理此文件的应用，请先导出副本") }
                settings = false
            }
            val uri = AttachmentDocuments.uri(context, id)
            OptionRow(AppIcons.FolderOpen, "在文件管理器中查看", "只读会话文件目录", false) {
                val folder = AttachmentDocuments.sessionUri(context, file.sessionId)
                val browse =
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                        )
                if (browse.resolveActivity(context.packageManager) != null) launch(browse)
                else {
                    MainActivity.current?.browseAttachmentFolder(folder)
                    settings = false
                }
            }
            OptionRow(AppIcons.ArrowOut, "在其他应用中打开", "原始文件 · 只读", false) {
                val intent =
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(file.name, uri)
                launch(Intent.createChooser(intent, "打开 ${file.name}"))
            }
            OptionRow(AppIcons.Description, "导出副本", "保存到你选择的位置，便于编辑", false) {
                MainActivity.current?.exportAttachment(id)
                settings = false
            }
            OptionRow(AppIcons.Paperclip, "分享文件", "", false) {
                val intent =
                    Intent(Intent.ACTION_SEND)
                        .setType(file.mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri(file.name, uri)
                launch(Intent.createChooser(intent, "分享文件"))
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    var zoom by remember(file) { mutableFloatStateOf(1f) }
    var pan by remember(file) { mutableStateOf(Offset.Zero) }
    var failed by remember(file) { mutableStateOf(false) }
    val drawable by
        produceState<android.graphics.drawable.Drawable?>(null, file) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) {
                            decoder,
                            info,
                            _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val scale = 2048f / maxOf(info.size.width, info.size.height, 2048)
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                        .getOrNull()
                }
            failed = value == null
        }
    val image = drawable
    if (image == null)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (failed) "图片无法解码，可通过右上角打开原文件。" else "正在读取图片…",
                color = WhaleMuted,
                modifier = Modifier.padding(24.dp),
            )
        }
    else
        Box(
            Modifier.fillMaxSize()
                .clip(ContinuousShape(0.dp))
                .testTag("image-preview-canvas")
                .semantics { stateDescription = "缩放 ${(zoom * 100).toInt()}%" }
                .pointerInput(file) {
                    detectTransformGestures { _, offset, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        pan =
                            if (zoom <= 1f) Offset.Zero
                            else
                                (pan + offset).let {
                                    Offset(
                                        it.x.coerceIn(
                                            -size.width * zoom / 2,
                                            size.width * zoom / 2,
                                        ),
                                        it.y.coerceIn(
                                            -size.height * zoom / 2,
                                            size.height * zoom / 2,
                                        ),
                                    )
                                }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = {
                    it.setImageDrawable(image)
                    (image as? android.graphics.drawable.Animatable)?.start()
                },
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                onRelease = { (image as? android.graphics.drawable.Animatable)?.stop() },
            )
        }
}

@Composable
private fun PdfPreview(file: File) {
    var count by remember(file) { mutableIntStateOf(0) }
    var error by remember(file) { mutableStateOf("") }
    LaunchedEffect(file) {
        runCatching {
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    PdfRenderer(it).use { pdf -> pdf.pageCount.coerceAtMost(500) }
                }
            }
        }
            .onSuccess { count = it }
            .onFailure { error = "PDF 无法预览，可能已加密或损坏。可通过右上角打开原文件。" }
    }
    if (error.isNotBlank()) Text(error, Modifier.padding(24.dp), color = WhaleMuted)
    else
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items((0 until count).toList()) { index ->
                val bitmap by
                    produceState<Bitmap?>(null, file, index) {
                        value =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    ParcelFileDescriptor.open(
                                            file,
                                            ParcelFileDescriptor.MODE_READ_ONLY,
                                        )
                                        .use { descriptor ->
                                            PdfRenderer(descriptor).use { pdf ->
                                                pdf.openPage(index).use { page ->
                                                    val scale =
                                                        minOf(
                                                            1200f / page.width.coerceAtLeast(1),
                                                            1800f / page.height.coerceAtLeast(1),
                                                        )
                                                    Bitmap.createBitmap(
                                                            (page.width * scale)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                            (page.height * scale)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                            Bitmap.Config.ARGB_8888,
                                                        )
                                                        .also {
                                                            it.eraseColor(
                                                                android.graphics.Color.WHITE,
                                                            )
                                                            page.render(
                                                                it,
                                                                null,
                                                                null,
                                                                PdfRenderer.Page
                                                                    .RENDER_MODE_FOR_DISPLAY,
                                                            )
                                                        }
                                                }
                                            }
                                        }
                                }
                                    .getOrNull()
                            }
                    }
                Column {
                    if (bitmap != null)
                        Image(
                            bitmap!!.asImageBitmap(),
                            "PDF 第 ${index + 1} 页",
                            Modifier.fillMaxWidth().background(Color.White),
                        )
                    else
                        Box(
                            Modifier.fillMaxWidth().height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("载入第 ${index + 1} 页…", color = WhaleMuted)
                        }
                    Text(
                        "${index + 1} / $count",
                        fontSize = 10.sp,
                        color = WhaleMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                    )
                }
            }
        }
}

@Composable
private fun TextDocumentPreview(store: AttachmentStore, file: LocalAttachment) {
    var content by remember(file.id) { mutableStateOf("") }
    LaunchedEffect(file.id) {
        content =
            withContext(Dispatchers.IO) {
                runCatching { store.text(file.id, 200000) }
                    .getOrElse { it.message ?: "此格式暂不支持预览，可通过右上角打开原文件。" }
            }
    }
    SelectionContainer(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
                .background(Color.White, ContinuousShape(18.dp))
                .padding(20.dp),
        ) {
            if (file.name.substringAfterLast('.').lowercase() in setOf("docx", "xlsx", "pptx"))
                Text(
                    "文档内容预览 · 完整排版请打开原文件",
                    fontSize = 11.sp,
                    color = WhaleMuted,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            Text(content.ifEmpty { "（空白文档）" }, fontSize = 14.sp, lineHeight = 23.sp)
        }
    }
}
