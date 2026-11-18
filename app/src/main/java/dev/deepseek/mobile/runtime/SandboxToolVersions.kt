package dev.deepseek.mobile.runtime

import android.content.Context
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

private val VersionPackages = setOf("python3", "nodejs", "nodejs-current")

/**
 * The UI displays versions from Alpine's x86 repositories. apk checks signatures when installing.
 */
internal suspend fun sandboxToolVersions(
    context: Context,
): Map<AlpineRelease, Map<String, String>> = buildMap {
    for (release in AlpineRelease.entries) {
        val versions = linkedMapOf<String, String>()
        for (repo in listOf("main", "community")) {
            val bytes =
                RuntimeArtifacts.lightRepositoryFile(context, "$repo/x86/APKINDEX.tar.gz", release)
            versions.putAll(readAlpineToolVersions(bytes))
        }
        put(release, versions)
    }
}

internal fun readAlpineToolVersions(index: ByteArray): Map<String, String> =
    DataInputStream(GZIPInputStream(index.inputStream())).use { tar ->
        val header = ByteArray(512)
        var skipped = 0L
        while (true) {
            tar.readFully(header)
            if (header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
            val length = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toInt(8)
            require(length in 0..32 * 1024 * 1024)
            if (name.removePrefix("./") == "APKINDEX") {
                val data = ByteArray(length)
                tar.readFully(data)
                return@use buildMap {
                    for (record in data.toString(Charsets.UTF_8).split("\n\n")) {
                        val lines = record.lineSequence()
                        val nameLine = lines.firstOrNull { it.startsWith("P:") } ?: continue
                        val packageName = nameLine.removePrefix("P:")
                        if (packageName !in VersionPackages) continue
                        val version =
                            record
                                .lineSequence()
                                .firstOrNull { it.startsWith("V:") }
                                ?.removePrefix("V:") ?: continue
                        put(packageName, version)
                    }
                }
            }
            val padded = (length + 511L) / 512L * 512L
            skipped += padded
            require(skipped <= 32L * 1024 * 1024)
            check(tar.skipBytes(padded.toInt()) == padded.toInt()) { "Alpine 索引不完整" }
        }
        error("Alpine 软件索引缺少 APKINDEX")
    }
