package dev.deepseek.mobile

import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.runtime.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** ZIP fixtures are pushed to this emulator's private cache, never into APKs. */
class RuntimeImageImportTest {
    @Test
    fun importsBothIndependentPackagesWithoutBundledAssets() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(context.assets.list("").orEmpty().contains("runtime-bundle"))
        val images = RuntimeImages.get(context)
        val root = RuntimeArtifacts.directory(context).canonicalFile
        for ((kind, name) in listOf("alpine" to "dsha-lite.zip", "tools" to "dsha-tool.zip")) {
            val image = images.imageSpec(kind)
            val cached = File(root, image.name).canonicalFile
            check(cached.parentFile == root)
            if (cached.exists()) assertTrue(cached.delete())
            val selected = File(context.cacheDir, name)
            assertTrue("Push fixture $name before this test", selected.isFile)
            val imported = images.importZip(Uri.fromFile(selected))
            assertEquals(kind, imported.kind)
            assertTrue(images.hasImage(kind))
            assertEquals(image.bytes, cached.length())
        }
        assertTrue(images.hasImage("tools"))
        assertEquals(2, images.availableImages.value.size)
    }
}
