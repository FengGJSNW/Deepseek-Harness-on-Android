package dev.deepseek.mobile

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlassDefaultTest {
    @Test
    fun cleanInstallUsesFrostedGlassButKeepsAnExistingLiquidPreference() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("experience-v1", Context.MODE_PRIVATE)
        prefs.edit().remove("liquidGlassEnabled").commit()
        ExperienceSettings.initialize(context)
        assertFalse(ExperienceSettings.liquidGlassEnabled)
        prefs.edit().putBoolean("liquidGlassEnabled", true).commit()
        ExperienceSettings.initialize(context)
        assertTrue(ExperienceSettings.liquidGlassEnabled)
        prefs.edit().remove("liquidGlassEnabled").commit()
        ExperienceSettings.initialize(context)
    }
}
