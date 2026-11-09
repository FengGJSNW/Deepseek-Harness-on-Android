package dev.deepseek.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        WhaleActivity.ambientInteraction()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> WhaleActivity.touching = true
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                WhaleActivity.touching = false
                WhaleActivity.ambientInteraction()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private var pickerSession = ""
    private var configImport: ((String) -> Unit)? = null
    private var configExport = ""
    private val configPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val callback = configImport
            configImport = null
            if (uri != null && callback != null)
                lifecycleScope.launch {
                    try {
                        val text =
                            withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)!!.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(4096)
                                    while (output.size() < 98_305) {
                                        val count =
                                            input.read(
                                                buffer,
                                                0,
                                                minOf(buffer.size, 98_305 - output.size()),
                                            )
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                    require(output.size() <= 98_304) { "配置文件最多 96KB" }
                                    output.toString("UTF-8")
                                }
                            }
                        callback(text)
                    } catch (e: Exception) {
                        AgentService.current?.toast("未导入：${e.message}")
                    }
                }
        }
    private val configWriter =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            val text = configExport
            configExport = ""
            if (uri != null)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri, "wt")!!.use {
                                it.write(text.toByteArray())
                            }
                        }
                        AgentService.current?.toast("配置文件已导出")
                    } catch (e: Exception) {
                        AgentService.current?.toast("导出失败：${e.message}")
                    }
                }
        }

    fun importTerminalConfig(callback: (String) -> Unit) {
        configImport = callback
        configPicker.launch(arrayOf("*/*"))
    }

    fun exportTerminalConfig(name: String, text: String) {
        configExport = text
        configWriter.launch(name)
    }

    private var exportingFile = ""
    private val fileWriter =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*"),
        ) { uri ->
            val id = exportingFile
            exportingFile = ""
            if (uri != null && id.isNotEmpty())
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            AttachmentStore(this@MainActivity).file(id).inputStream().use { input ->
                                contentResolver.openOutputStream(uri, "wt")!!.use {
                                    input.copyTo(it)
                                }
                            }
                        }
                    }
                        .onSuccess { AgentService.current?.toast("文件副本已保存") }
                        .onFailure { AgentService.current?.toast("导出失败：${it.message}") }
                }
        }
    private val fileBrowser =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            result.data?.data?.let { uri ->
                if (uri.authority == AttachmentDocuments.authority(this))
                    runCatching {
                        val id =
                            android.provider.DocumentsContract.getDocumentId(uri).removePrefix("f:")
                        FilePreviewState.open(id)
                    }
            }
        }

    fun exportAttachment(id: String) {
        runCatching { AttachmentStore(this).get(id) }
            .onSuccess {
                exportingFile = id
                fileWriter.launch(it.name)
            }
            .onFailure { AgentService.current?.toast("文件已不可用") }
    }

    fun browseAttachmentFolder(uri: android.net.Uri) {
        runCatching {
            fileBrowser.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri),
            )
        }
            .onFailure { AgentService.current?.toast("文件管理器暂不可用，可导出副本后查看") }
    }

    private var bubbleStickerCallback: ((String) -> Unit)? = null
    private val bubbleStickerPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri ->
            val callback = bubbleStickerCallback
            bubbleStickerCallback = null
            if (uri != null && callback != null)
                lifecycleScope.launch {
                    runCatching {
                            withContext(Dispatchers.IO) {
                                val store = AttachmentStore(this@MainActivity)
                                val info = store.import(uri, AssistantState.selectedId)
                                require(
                                    info.mime.startsWith("image/") && info.mime != "image/svg+xml",
                                ) {
                                    "请选择图片表情包"
                                }
                                // Decode now to reject unusable content before saving its
                                // preference.
                                android.graphics.ImageDecoder.decodeDrawable(
                                    android.graphics.ImageDecoder.createSource(store.file(info.id)),
                                )
                                info.id
                            }
                        }
                        .onSuccess(callback)
                        .onFailure { AgentService.current?.toast("表情包导入失败：${it.message}") }
                }
        }

    fun pickBubbleSticker(callback: (String) -> Unit) {
        bubbleStickerCallback = callback
        bubbleStickerPicker.launch("image/*")
    }

    private val imagePicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) AgentService.current?.importAttachment(uri, pickerSession, true)
        }
    private val textPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) AgentService.current?.importAttachment(uri, pickerSession, false)
        }
    private var officialFilesResult: ((List<android.net.Uri>) -> Unit)? = null
    private val officialFilesPicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            val callback = officialFilesResult
            officialFilesResult = null
            callback?.invoke(uris)
        }

    fun pickOfficialAttachments(callback: (List<android.net.Uri>) -> Unit) {
        officialFilesResult = callback
        runCatching { officialFilesPicker.launch(arrayOf("*/*")) }
            .onFailure {
                officialFilesResult = null
                OfficialChatNavigation.reportAttachmentPickerFailure()
            }
    }

    private val workspacePicker =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    AssistantState.sessions
                        .find { it.id == pickerSession }
                        ?.let {
                            it.workspace = uri.toString()
                            it.fileTools = true
                        }
                    AssistantState.persist()
                } catch (_: Exception) {
                    AgentService.current?.toast("无法取得目录访问权限")
                }
            }
        }

    fun pickImage() {
        pickerSession = AssistantState.selectedId
        imagePicker.launch("image/*")
    }

    fun pickText() {
        pickerSession = AssistantState.selectedId
        textPicker.launch("*/*")
    }

    fun pickWorkspace() {
        pickerSession = AssistantState.selectedId
        workspacePicker.launch(null)
    }

    fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("sessionId")?.let { AssistantState.select(it) }
    }

    companion object {
        var current: MainActivity? = null
        internal var inForeground = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        startForegroundService(
            Intent(this, AgentService::class.java)
                .putExtra("sessionId", intent.getStringExtra("sessionId")),
        )
        intent.getStringExtra("sessionId")?.let { AssistantState.selectedId = it }
        setContent { FishApp() }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        current = this
        AgentService.current?.terminal?.connections?.refresh()
        AgentService.current?.perform("status")
        AgentService.current?.refreshFloating()
    }

    override fun onPause() {
        inForeground = false
        AgentService.current?.refreshFloating()
        WhaleActivity.touching = false
        if (AssistantState.sessions.isNotEmpty()) AssistantState.persist()
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        AgentService.current?.perform("status")
    }

    fun requestAccessibility() {
        AssistantState.showAccessibilityInfo = true
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }
}
