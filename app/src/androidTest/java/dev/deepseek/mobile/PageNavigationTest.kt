package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Emulator-only fixtures: never insert sessions or change settings on the user's phone. */
class PageNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepare() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.addSession()
            AssistantState.petEnabled = true
        }
    }

    @Test
    fun settingsCategoriesAndAppearancePagesCanBeOpenedAndReturnedFrom() {
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme { FontEnvironment { SettingsScreen(onCreatePlugin = {}) {} } }
            }
        }
        for (title in listOf("记忆", "API Key", "Agent 行为", "权限", "Sandbox", "扩展", "数据备份", "关于")) {
            android.util.Log.i("DSHA-PageTest", "Opening settings: $title")
            ui.onNodeWithText(title).performScrollTo().performClick()
            ui.waitForIdle()
            ui.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
            ui.waitForIdle()
        }
        ui.onNodeWithText("外观").performScrollTo().performClick()
        for (title in listOf("字体", "液态玻璃（实验性）", "桌宠")) {
            android.util.Log.i("DSHA-PageTest", "Opening appearance: $title")
            ui.onNodeWithText(title).performScrollTo().performClick()
            if (title == "桌宠") {
                ui.onNodeWithContentDescription("文案1详情").performScrollTo().assertIsDisplayed()
            }
            ui.waitForIdle()
            ui.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
            ui.waitForIdle()
        }
    }

    @Test
    fun actualUserBrowserEntryCanOpenAndReturnRepeatedly() {
        repeat(3) {
            ui.runOnUiThread { WorkspaceState.open(WorkspaceTab.Home) }
            ui.onNodeWithText("内置浏览器").performScrollTo().performClick()
            ui.onNodeWithTag("workspace-browser").assertIsDisplayed()
            ui.onNodeWithTag("user-browser-address").assertIsDisplayed()
            ui.onNodeWithContentDescription("返回会话").performClick()
            ui.onNodeWithTag("workspace-browser").assertDoesNotExist()
            ui.runOnUiThread { WorkspaceState.closeChromePages() }
        }
    }

    @Test
    fun workspaceAccessAndToolTabsSurviveRepeatedNavigation() {
        val tabs =
            listOf(
                WorkspaceTab.Home,
                WorkspaceTab.SandboxAccess,
                WorkspaceTab.SandboxDisks(),
                WorkspaceTab.RemoteAccess(RemoteTarget.SSH),
                WorkspaceTab.RemoteAccess(RemoteTarget.COMPUTER),
                WorkspaceTab.Workflow,
                WorkspaceTab.Agents,
                WorkspaceTab.Files,
            )
        repeat(2) {
            for (tab in tabs) {
                android.util.Log.i("DSHA-PageTest", "Opening workspace: $tab")
                ui.runOnUiThread { WorkspaceState.open(tab) }
                ui.onNodeWithTag("conversation-workspace").assertIsDisplayed()
                ui.waitForIdle()
                ui.runOnUiThread { WorkspaceState.close(tab) }
                ui.waitForIdle()
            }
        }
    }
}
