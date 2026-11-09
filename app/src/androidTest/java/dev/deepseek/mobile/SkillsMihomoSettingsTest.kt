package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class SkillsMihomoSettingsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsExposeSkillsAndMihomoManagement() {
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme { FontEnvironment { SettingsScreen(onCreatePlugin = {}) {} } }
            }
        }
        ui.onNodeWithText("扩展").performScrollTo().performClick()
        ui.onNodeWithText("Skills").performClick()
        ui.onNodeWithText("导入技能包").assertIsDisplayed()
        ui.onNodeWithText("skill-creator").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("Mihomo 代理").performScrollTo().performClick()
        ui.onNodeWithText("导入订阅").performScrollTo().performClick()
        ui.onNodeWithText("HTTPS 订阅地址").assertIsDisplayed()
    }
}
