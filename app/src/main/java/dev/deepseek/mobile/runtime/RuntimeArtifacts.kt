package dev.deepseek.mobile.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Immutable distribution inputs. Nothing from the guest can change these URLs or hashes. */
internal object RuntimeArtifacts {
    data class Artifact(val name: String, val url: String, val sha256: String, val bytes: Long)

    val artifacts =
        listOf(
            Artifact(
                "kernel.bin",
                "https://i.copy.sh/buildroot-bzimage68.bin",
                "507a759c70ab7a490a233be454d0b5b88bc667956a410b531cb4edc091e2eb1c",
                10068480,
            ),
            Artifact(
                "seabios.bin",
                "https://copy.sh/v86/bios/seabios.bin",
                "73e3f359102e3a9982c35fce98eb7cd08f18303ac7f1ba6ebfbe6cdc1c244d98",
                131072,
            ),
            Artifact(
                "vgabios.bin",
                "https://copy.sh/v86/bios/vgabios.bin",
                "a4bc0d80cc3ca028c73dafa8fee396b8d054ce87ebd8abfbd31b06b437607880",
                36352,
            ),
            Artifact(
                "alpine.tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86/alpine-minirootfs-3.24.3-x86.tar.gz",
                "a73bd9ed20a5ed1503a0688957add539e10340c7ca1d9e8c06c34a76ddc5f715",
                3545963,
            ),
        )
    private val lock = Mutex()

    fun directory(context: Context) =
        File(context.filesDir, "runtimes/distributions/v86-linux68-v1")

    suspend fun ensure(context: Context, progress: (String) -> Unit = {}) = lock.withLock {
        withContext(Dispatchers.IO) {
            val dir = directory(context).apply { mkdirs() }
            artifacts.forEach { spec ->
                val file = File(dir, spec.name)
                if (file.isFile && file.length() == spec.bytes && hash(file) == spec.sha256)
                    return@forEach
                progress("下载并校验 ${spec.name}")
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    val connection =
                        URL(spec.url).openConnection().apply {
                            connectTimeout = 20000
                            readTimeout = 30000
                        }
                    connection.getInputStream().use { input ->
                        val buffer = ByteArray(65536)
                        var count = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            check(count <= spec.bytes) { "Runtime image exceeds pinned size" }
                            output.write(buffer, 0, n)
                        }
                        check(count == spec.bytes) { "Runtime image download incomplete" }
                    }
                    output.flush()
                    check(hash(atomic.newFile()) == spec.sha256) {
                        "Runtime image SHA-256 mismatch"
                    }
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }
    }

    // AtomicFile's new-file path is stable on supported Android versions; hash bytes before commit.
    private fun AtomicFile.newFile() = File(baseFile.path + ".new")

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b = ByteArray(65536)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun manifest() =
        JSONObject()
            .put("engine", "v86 0.5.473 (BSD-2-Clause)")
            .put("architecture", "x86")
            .put(
                "downloads",
                JSONArray(
                    artifacts.map {
                        JSONObject()
                            .put("name", it.name)
                            .put("url", it.url)
                            .put("sha256", it.sha256)
                            .put("bytes", it.bytes)
                    },
                ),
            )
}
