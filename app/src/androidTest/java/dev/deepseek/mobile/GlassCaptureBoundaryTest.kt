package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class GlassCaptureBoundaryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun inlineGlassCannotRecursivelySampleItsOwnRecordedContent() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        ui.waitUntil(30000) { AssistantState.ready }
        val previous = ExperienceSettings.liquidGlassEnabled
        var inlineAllowed: Boolean? = null
        var headerAllowed: Boolean? = null
        var clicks = 0
        try {
            ui.runOnUiThread {
                ui.activity.setContent {
                    MaterialTheme {
                        FontEnvironment {
                            FadingHeaderLayout(
                                header = {
                                    val source = LocalGlassBackdrop.current
                                    Box(
                                        Modifier.onGloballyPositioned {
                                            headerAllowed = source?.canSampleFrom(it)
                                        },
                                    ) {
                                        SmallIcon(AppIcons.ArrowBack, "页头按钮") { clicks++ }
                                    }
                                },
                            ) { inset ->
                                val source = LocalGlassBackdrop.current
                                Column(Modifier.fillMaxSize().padding(top = inset + 16.dp)) {
                                    Text("内容区域的按钮不能采样它自己")
                                    Box(
                                        Modifier.onGloballyPositioned {
                                            inlineAllowed = source?.canSampleFrom(it)
                                        },
                                    ) {
                                        SmallIcon(AppIcons.Settings, "内容按钮") { clicks++ }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (enabled in listOf(false, true, false)) {
                ui.runOnUiThread { ExperienceSettings.liquidGlassEnabled = enabled }
                ui.onNodeWithContentDescription("内容按钮").performClick()
                ui.onNodeWithContentDescription("页头按钮").performClick()
                ui.waitForIdle()
                ui.runOnUiThread {
                    assertEquals(false, inlineAllowed)
                    assertEquals(true, headerAllowed)
                }
            }
            assertEquals(6, clicks)
        } finally {
            ui.runOnUiThread { ExperienceSettings.liquidGlassEnabled = previous }
        }
    }
}
