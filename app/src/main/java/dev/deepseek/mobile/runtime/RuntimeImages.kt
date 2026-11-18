package dev.deepseek.mobile.runtime

import android.content.Context
import android.net.Uri
import dev.deepseek.mobile.harness.boundedBytes
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class RuntimeImagePackage(
    val kind: String,
    val release: String,
    val image: String,
    val files: List<RuntimeArtifacts.Artifact>,
)

/** ZIP paths and checksums are matched against the APK's trusted catalog before any commit. */
internal suspend fun unpackRuntimeImage(
    input: InputStream,
    catalog: List<RuntimeImagePackage>,
    staging: File,
    progress: (String) -> Unit = {},
): RuntimeImagePackage {
    val maximum = catalog.maxOf { p -> p.files.sumOf { it.bytes } } + 1024 * 1024
    val bounded =
        object : InputStream() {
            var readBytes = 0L

            fun counted(n: Int): Int {
                if (n > 0) readBytes += n
                require(readBytes <= maximum) { "镜像 ZIP 超过大小限制" }
                return n
            }

            override fun read(): Int = input.read().also { if (it >= 0) counted(1) }

            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                counted(input.read(buffer, offset, length))

            override fun close() = input.close()
        }
    ZipInputStream(bounded).use { zip ->
        require(zip.nextEntry?.name == "manifest.json") { "请选择 DSHA 发布的镜像 ZIP（缺少清单）" }
        val manifest = JSONObject(String(zip.boundedBytes(65536), Charsets.UTF_8))
        require(
            manifest.optString("format") == "dsha-sandbox-image" && manifest.optInt("schema") == 1,
        ) {
            "不支持的镜像格式"
        }
        val selected =
            catalog.firstOrNull {
                it.kind == manifest.optString("kind") &&
                    it.release == manifest.optString("release") &&
                    it.image == manifest.optString("image")
            } ?: error("此镜像与当前 DSHA 版本不兼容")
        val declared = manifest.getJSONObject("files")
        require(declared.keys().asSequence().toSet() == selected.files.map { it.name }.toSet()) {
            "镜像清单不完整"
        }
        for (spec in selected.files) {
            val item = declared.getJSONObject(spec.name)
            require(
                item.getLong("bytes") == spec.bytes && item.getString("sha256") == spec.sha256,
            ) {
                "镜像版本校验不匹配"
            }
        }
        val seen = mutableSetOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            val spec = selected.files.firstOrNull { it.name == entry.name } ?: error("ZIP 包含未知文件")
            require(!entry.isDirectory && seen.add(spec.name)) { "ZIP 包含重复或无效文件" }
            // All destinations come from the trusted catalog, never an arbitrary archive path.
            val target = File(staging, spec.name)
            require(target.canonicalFile.parentFile == staging.canonicalFile) { "无效镜像路径" }
            progress("校验 ${spec.name}")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            target.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = zip.read(buffer)
                    if (n < 0) break
                    bytes += n
                    require(bytes <= spec.bytes) { "镜像大小超过清单限制" }
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            require(
                bytes == spec.bytes &&
                    digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256,
            ) {
                "镜像校验失败，请重新下载 ZIP"
            }
        }
        require(seen == selected.files.map { it.name }.toSet()) { "ZIP 缺少镜像文件" }
        return selected
    }
}

internal class RuntimeImages private constructor(private val context: Context) {
    val catalog: List<RuntimeImagePackage> =
        context.assets.open("runtime-images.json").bufferedReader().use { reader ->
            val packages = JSONObject(reader.readText()).getJSONObject("packages")
            packages
                .keys()
                .asSequence()
                .map { kind ->
                    val p = packages.getJSONObject(kind)
                    val files = p.getJSONObject("files")
                    RuntimeImagePackage(
                        kind,
                        p.getString("release"),
                        p.getString("image"),
                        files
                            .keys()
                            .asSequence()
                            .map { name ->
                                val spec = files.getJSONObject(name)
                                RuntimeArtifacts.Artifact(
                                    name,
                                    "",
                                    spec.getString("sha256"),
                                    spec.getLong("bytes"),
                                )
                            }
                            .toList(),
                    )
                }
                .toList()
        }
    private val importPreferences =
        context.getSharedPreferences("runtime-zip-sources", Context.MODE_PRIVATE)

    suspend fun importSource(kind: String): Pair<String, Boolean>? =
        withContext(Dispatchers.IO) {
            val previous = importPreferences.getString(kind, null) ?: return@withContext null
            val uri = Uri.parse(previous)
            val path =
                if (uri.authority == "com.android.externalstorage.documents") {
                    runCatching { android.provider.DocumentsContract.getDocumentId(uri) }
                        .getOrNull()
                        ?.let {
                            if (it.startsWith("primary:"))
                                File(
                                        android.os.Environment.getExternalStorageDirectory(),
                                        it.substringAfter(':'),
                                    )
                                    .path
                            else "/storage/" + it.replaceFirst(':', '/')
                        } ?: previous
                } else previous
            val exists = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }
                .getOrDefault(false)
            path to exists
        }

    private val lock = Mutex()
    private val imported = MutableStateFlow(available())
    val availableImages = imported.asStateFlow()

    private fun available() =
        catalog
            .filter { p ->
                p.files.all { spec ->
                    File(RuntimeArtifacts.directory(context), spec.name).let {
                        it.isFile && it.length() == spec.bytes
                    }
                }
            }
            .map { it.kind }
            .toSet()

    fun imageSpec(kind: String) =
        catalog.first { it.kind == kind }.let { p -> p.files.first { it.name == p.image } }

    fun hasImage(kind: String) = kind in available()

    suspend fun toolPackage(relative: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(
                relative.matches(
                    Regex("(?:main|community)/x86/(?:APKINDEX[.]tar[.]gz|[A-Za-z0-9._+~-]+[.]apk)"),
                ),
            )
            val name = "tools-" + relative.replace("/x86/", "-")
            val spec =
                catalog.first { it.kind == "tools" }.files.firstOrNull { it.name == name }
                    ?: error("DSHA Tool 缺少 $relative，请重新导入工具包或改用官方软件源")
            val file = File(RuntimeArtifacts.directory(context), spec.name)
            check(file.isFile && file.length() == spec.bytes) { "本机工具包文件丢失，请重新导入" }
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            check(hash.joinToString("") { "%02x".format(it) } == spec.sha256) { "本机工具包文件已损坏，请重新导入" }
            bytes
        }

    suspend fun importZip(uri: Uri, progress: (String) -> Unit = {}): RuntimeImagePackage =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val parent =
                    File(context.cacheDir, "runtime-image-imports").canonicalFile.apply { mkdirs() }
                val staging = File(parent, UUID.randomUUID().toString()).canonicalFile
                check(staging.parentFile == parent)
                staging.mkdirs()
                try {
                    val stream = context.contentResolver.openInputStream(uri) ?: error("无法读取所选 ZIP")
                    val image = unpackRuntimeImage(stream, catalog, staging, progress)
                    progress("保存已校验的镜像")
                    RuntimeArtifacts.importVerified(context, image.files, staging)
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    importPreferences.edit().putString(image.kind, uri.toString()).apply()
                    imported.value = available()
                    image
                } finally {
                    // Only this import's private staging folder; existing images/VMs are untouched.
                    check(staging.canonicalFile.parentFile == parent)
                    staging.deleteRecursively()
                }
            }
        }

    companion object {
        @Volatile private var instance: RuntimeImages? = null

        fun get(context: Context): RuntimeImages =
            instance
                ?: synchronized(this) {
                    instance ?: RuntimeImages(context.applicationContext).also { instance = it }
                }
    }
}
