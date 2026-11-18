package dev.deepseek.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.deepseek.mobile.harness.SkillStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BuiltinToolGuidesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun guidesAreDiscoverableWithoutInstallingOrEnablingUserSkills() {
        val store = SkillStore(context)
        assertTrue(store.list().isEmpty())
        val catalog = store.catalog("") { true }
        assertEquals(5, catalog.length())
        for (i in 0 until catalog.length()) {
            val name = catalog.getJSONObject(i).getString("name")
            val guide = store.read("", name, "SKILL.md", 0)
            assertTrue(guide.getString("text").contains("name: $name"))
            assertTrue(guide.getBoolean("builtin"))
        }
        assertEquals(0, store.catalog("") { false }.length())
    }

    @Test
    fun arbitraryNamesAndPathsCannotReadApplicationAssets() {
        val store = SkillStore(context)
        assertThrows(IllegalArgumentException::class.java) {
            store.read("", "../../secrets", "SKILL.md", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.read("", "dsha-mcp", "../../harness.js", 0)
        }
        val empty = store.read("", "dsha-mcp", "SKILL.md", Int.MAX_VALUE)
        assertEquals("", empty.getString("text"))
        assertTrue(empty.isNull("nextOffset"))
    }
}
