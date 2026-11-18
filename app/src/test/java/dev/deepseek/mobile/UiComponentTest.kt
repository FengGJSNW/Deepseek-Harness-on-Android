package dev.deepseek.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiComponentTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val effects = mutableListOf<Int>()
    private val haptics =
        UiHaptics(
            { true },
            {
                effects.add(it)
                true
            },
            { android.os.SystemClock.uptimeMillis() },
            35,
        )

    private fun content(scale: Float = 1f, rtl: Boolean = false, block: @Composable () -> Unit) {
        AssistantState.initialize(ui.activity)
        ui.setContent {
            CompositionLocalProvider(
                LocalUiHaptics provides haptics,
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme { Surface(color = Color.White) { block() } }
            }
        }
    }

    @Test
    fun workspaceToggleKeepsItsPositionAndIdentityWhileTheDashboardDisappears() {
        val progress = mutableStateOf(0f)
        var opened by mutableStateOf(false)
        content {
            Column(Modifier.width(360.dp)) {
                ConversationTitleBar("DSHA", "打开侧栏", {}, progress, opened, null)
            }
        }
        val toggle = ui.onNodeWithTag("workspace-tabs-toggle")
        val before = toggle.fetchSemanticsNode()
        val center = before.boundsInRoot.center
        ui.onNodeWithTag("context-usage-toggle").assertIsDisplayed()
        ui.runOnIdle {
            opened = true
            progress.value = .4f
        }
        val middle = toggle.assertIsEnabled().fetchSemanticsNode()
        assertEquals(before.id, middle.id)
        assertEquals(center.x, middle.boundsInRoot.center.x, 1f)
        ui.onNodeWithContentDescription("打开功能区").assertDoesNotExist()
        ui.runOnIdle { progress.value = 1f }
        val after = toggle.assertIsEnabled().fetchSemanticsNode()
        assertEquals(before.id, after.id)
        assertEquals(center.x, after.boundsInRoot.center.x, 1f)
        ui.onNodeWithTag("context-usage-toggle").assertDoesNotExist()
        ui.runOnIdle {
            opened = false
            progress.value = 0f
        }
        ui.onNodeWithTag("context-usage-toggle").assertIsEnabled()
        assertEquals(before.id, toggle.fetchSemanticsNode().id)
    }

    @Test
    fun titleStartsImmediatelyAfterNavigationWithUnequalActionWidths() {
        content {
            Column(Modifier.width(360.dp)) {
                ConversationTitleBar(
                    "DSHA",
                    "打开侧栏",
                    {},
                    remember { mutableStateOf(0f) },
                    false,
                    null,
                )
            }
        }
        val title = ui.onNodeWithTag("conversation-title").fetchSemanticsNode().boundsInRoot
        val navigation = ui.onNodeWithContentDescription("打开侧栏").fetchSemanticsNode().boundsInRoot
        assertEquals(navigation.right + 8f, title.left, 1f)
        val context = ui.onNodeWithTag("context-usage-toggle").fetchSemanticsNode().boundsInRoot
        assertEquals(navigation.center.y, title.center.y, 1f)
        assertTrue(title.right <= context.left)
        assertTrue(title.left >= navigation.right)
    }

    @Test
    fun longTitleFollowsNavigationAndStaysClearOfActionsInRtlWithLargeText() {
        content(scale = 1.5f, rtl = true) {
            Column(Modifier.width(320.dp)) {
                ConversationTitleBar(
                    "一个需要截断且不遮挡按钮的很长会话标题",
                    "打开侧栏",
                    {},
                    remember { mutableStateOf(0f) },
                    false,
                    null,
                )
            }
        }
        val title = ui.onNodeWithTag("conversation-title").fetchSemanticsNode().boundsInRoot
        val navigation = ui.onNodeWithContentDescription("打开侧栏").fetchSemanticsNode().boundsInRoot
        assertEquals(navigation.left - 8f, title.right, 1f)
        for (node in
            listOf(
                ui.onNodeWithContentDescription("打开侧栏"),
                ui.onNodeWithTag("context-usage-toggle"),
            )) {
            assertFalse(title.overlaps(node.fetchSemanticsNode().boundsInRoot))
        }
    }

    @Test
    fun entireToggleRowIsOneAccessibleControlAndOnlyUserClicksVibrate() {
        var value by mutableStateOf(false)
        var changes = 0
        content {
            Column(Modifier.width(320.dp)) {
                ToggleSetting("触感反馈", "跟随系统设置", value) {
                    value = it
                    changes++
                }
            }
        }
        ui.onAllNodes(hasClickAction()).assertCountEquals(1)
        ui.onNodeWithText("触感反馈").performClick().assertIsOn()
        assertEquals(1, changes)
        assertEquals(1, effects.size)
        ui.runOnIdle { value = false }
        ui.waitForIdle()
        assertEquals(1, effects.size)
    }

    @Test
    fun busyToggleCannotChangeOrEmitHaptics() {
        var changes = 0
        content {
            ToggleSetting("终端命令", "支持代理环境变量的命令", true, enabled = false) { changes++ }
        }
        ui.onNodeWithText("终端命令").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, changes)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun draggingAListDoesNotActivateOrVibrateItsRows() {
        var clicks = 0
        content {
            Column(Modifier.width(320.dp).height(240.dp).verticalScroll(rememberScrollState())) {
                repeat(15) { OptionRow(AppIcons.Spark, "选项 $it", "说明", false) { clicks++ } }
            }
        }
        ui.onNodeWithText("选项 1").performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals(0, clicks)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun selectedOptionDoesNotTickAgainAndSelectionKeepsTextAligned() {
        var selected by mutableStateOf(false)
        content {
            Column(Modifier.width(320.dp)) {
                OptionRow(AppIcons.Spark, "选项", "较长的详细说明，用于验证尾部预留的空间不会随选中状态改变。", selected) {
                    selected = true
                }
            }
        }
        val before =
            ui.onNodeWithText("选项", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        ui.onNodeWithText("选项").performClick().assertIsSelected()
        val after =
            ui.onNodeWithText("选项", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        ui.onNodeWithText("选项").performClick()
        assertEquals(1, effects.size)
    }

    @Test
    fun sliderRejectsTrackTapAndVerticalDragButTicksOnDeliberateChange() {
        var value by mutableFloatStateOf(.5f)
        content {
            Column(Modifier.width(320.dp)) {
                CleanSlider(value, { value = it }, valueRange = 0f..1f)
            }
        }
        val slider = ui.onNodeWithTag("parameter-slider")
        slider.performTouchInput { click(centerRight) }
        slider.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 100f))
            up()
        }
        assertEquals(.5f, value, .0001f)
        assertTrue(effects.isEmpty())
        slider.performTouchInput {
            down(center)
            moveTo(Offset(width - 16f, centerY))
            up()
        }
        assertTrue(value > .9f)
        assertTrue(effects.isNotEmpty())
        effects.clear()
        val saved = value
        slider.performTouchInput {
            down(Offset(width - 16f, centerY))
            moveTo(center)
            cancel()
        }
        assertEquals(saved, value, .0001f)
    }

    @Test
    fun disabledSendHasNoFeedbackAndEnabledSendInvokesOnce() {
        var enabled by mutableStateOf(false)
        var sends = 0
        content { ComposerSendButton(enabled) { sends++ } }
        ui.onNodeWithTag("composer-send").performTouchInput { click() }
        assertTrue(effects.isEmpty())
        assertEquals(0, sends)
        ui.runOnIdle { enabled = true }
        ui.onNodeWithTag("composer-send").performClick()
        assertEquals(1, effects.size)
        assertEquals(1, sends)
    }

    @Test
    fun settingsRowsGrowForLargeFontsAndRenderAReviewImage() {
        content(scale = 1.3f) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ConversationTitleBar(
                    "DSHA",
                    "打开侧栏",
                    {},
                    remember { mutableStateOf(0f) },
                    false,
                    null,
                )
                Text("设置", Modifier.padding(vertical = 16.dp), fontSize = 20.sp)
                SettingsGroup {
                    SettingsLink(AppIcons.Image, "外观", "桌宠、字体与阅读") {}
                    SettingsLink(AppIcons.Description, "记忆", "管理、全局记忆与摘要") {}
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup { ToggleSetting("触感反馈", "开关、换挡与发送时轻振动，遵循系统设置", true) {} }
                Spacer(Modifier.height(16.dp))
                OptionRow(AppIcons.Spark, "执行", "在授权范围内使用工具，执行并验证任务", true) {}
                OptionRow(AppIcons.Description, "规划", "先调查和制定计划", false) {}
                CleanSlider(.5f, {}, valueRange = 0f..1f)
            }
        }
        ui.onNodeWithText("触感反馈").assertIsDisplayed()
        val title =
            ui.onNodeWithText("触感反馈", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val detail =
            ui.onNodeWithText("开关、换挡与发送时轻振动，遵循系统设置", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(detail.top >= title.bottom)
        ui.runOnIdle {
            val view = ui.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/085-components.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun hapticsPreferenceSurvivesReloadAndCanBeReenabled() {
        val settings = ui.activity.getSharedPreferences("experience-v1", 0)
        settings.edit().remove("hapticsEnabled").commit()
        ExperienceSettings.initialize(ui.activity)
        assertTrue(ExperienceSettings.hapticsEnabled)
        ExperienceSettings.hapticsEnabled = false
        ExperienceSettings.save()
        ExperienceSettings.initialize(ui.activity)
        assertFalse(ExperienceSettings.hapticsEnabled)
        ExperienceSettings.hapticsEnabled = true
        ExperienceSettings.save()
        ExperienceSettings.initialize(ui.activity)
        assertTrue(ExperienceSettings.hapticsEnabled)
    }
}
