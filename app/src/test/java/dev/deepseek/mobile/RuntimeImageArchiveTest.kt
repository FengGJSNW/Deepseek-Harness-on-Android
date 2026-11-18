package dev.deepseek.mobile

import dev.deepseek.mobile.runtime.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeImageArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val expected = "verified image".toByteArray()
    private val spec =
        RuntimeArtifacts.Artifact(
            "root.tar.gz",
            "",
            MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") {
                "%02x".format(it)
            },
            expected.size.toLong(),
        )
    private val image = RuntimeImagePackage("light", "test", spec.name, listOf(spec))

    private fun archive(
        data: ByteArray = expected,
        extra: String? = null,
        missing: Boolean = false,
        falseHash: Boolean = false,
    ): ByteArray {
        val metadata =
            JSONObject()
                .put("format", "dsha-sandbox-image")
                .put("schema", 1)
                .put("kind", image.kind)
                .put("release", image.release)
                .put("image", image.image)
                .put(
                    "files",
                    JSONObject()
                        .put(
                            spec.name,
                            JSONObject()
                                .put("bytes", spec.bytes)
                                .put("sha256", if (falseHash) "0".repeat(64) else spec.sha256),
                        ),
                )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            fun entry(name: String, body: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(body)
                out.closeEntry()
            }
            entry("manifest.json", metadata.toString().toByteArray())
            if (!missing) entry(spec.name, data)
            if (extra != null) entry(extra, expected)
        }
        return bytes.toByteArray()
    }

    @Test
    fun validArchiveIsVerifiedAgainstTrustedCatalog() = runBlocking {
        val staging = temporary.newFolder()
        assertEquals(image, unpackRuntimeImage(archive().inputStream(), listOf(image), staging))
        assertArrayEquals(expected, File(staging, spec.name).readBytes())
    }

    @Test
    fun changedPayloadAndForgedManifestAreRejected() = runBlocking {
        assertTrue(
            runCatching {
                unpackRuntimeImage(
                    archive("invalid image!".toByteArray()).inputStream(),
                    listOf(image),
                    temporary.newFolder(),
                )
            }
                .isFailure,
        )
        assertTrue(
            runCatching {
                unpackRuntimeImage(
                    archive(falseHash = true).inputStream(),
                    listOf(image),
                    temporary.newFolder(),
                )
            }
                .isFailure,
        )
    }

    @Test
    fun missingEntriesAndPathTraversalAreRejected() = runBlocking {
        assertTrue(
            runCatching {
                unpackRuntimeImage(
                    archive(missing = true).inputStream(),
                    listOf(image),
                    temporary.newFolder(),
                )
            }
                .isFailure,
        )
        assertTrue(
            runCatching {
                unpackRuntimeImage(
                    archive(extra = "../outside").inputStream(),
                    listOf(image),
                    temporary.newFolder(),
                )
            }
                .isFailure,
        )
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test
    fun expandedPayloadCannotExceedPinnedLength() = runBlocking {
        assertTrue(
            runCatching {
                unpackRuntimeImage(
                    archive(ByteArray(200000)).inputStream(),
                    listOf(image),
                    temporary.newFolder(),
                )
            }
                .isFailure,
        )
    }
}
