package dev.deepseek.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.deepseek.mobile.proxy.MihomoState
import dev.deepseek.mobile.workflow.PlanStep
import dev.deepseek.mobile.workflow.VerificationRecord
import dev.deepseek.mobile.workflow.WorkMode
import dev.deepseek.mobile.workflow.WorkPlan
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
class DshaStyleTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    private fun screen(content: @Composable () -> Unit) {
        ui.setContent {
            MaterialTheme { FontEnvironment { Surface(color = DshaSurface.page) { content() } } }
        }
        ui.waitUntil(20000) { DshaFonts.ready }
    }

    private fun bitmap(): Bitmap {
        val view = ui.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        ui.runOnIdle {
            val file = File("build/reports/ui/$name.png")
            file.parentFile?.mkdirs()
            val bitmap = bitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun settingsUseTheProductionPageWithWorkingNavigation() {
        AssistantState.sessions.clear()
        AssistantState.projects.clear()
        AssistantState.projects.add(WorkProject("preview-project", "界面项目"))
        AssistantState.sessions.add(
            Conversation("preview", "界面微调").apply { projectId = "preview-project" },
        )
        AssistantState.selectedId = "preview"
        AssistantState.appMode = "work"
        WorkspaceState.reset()
        val petEnabled = AssistantState.petEnabled
        AssistantState.petEnabled = false
        ui.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(ui.activity.window, false) }
        ui.setContent { FishApp() }
        ui.waitUntil(20000) { DshaFonts.ready }
        ui.runOnIdle {
            val root = ui.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            ViewCompat.dispatchApplyWindowInsets(
                root,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 28, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                    .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                    .build(),
            )
        }
        capture("085-composer-solid")
        ui.onNodeWithContentDescription("打开功能区").performClick()
        capture("085-workspace-header")
        ui.onNodeWithText("计划与验证").performClick()
        ui.onNodeWithTag("workflow-workspace").assertIsDisplayed()
        capture("085-workflow-empty")
        ui.onNodeWithContentDescription("返回会话").performClick()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("项目操作：界面项目").assertIsDisplayed()
        ui.onNodeWithContentDescription("会话操作：界面微调").assertIsDisplayed()
        val footer = ui.onNodeWithTag("drawer-bottom-actions").fetchSemanticsNode().boundsInRoot
        val newChat = ui.onNodeWithTag("drawer-new-chat").fetchSemanticsNode().boundsInRoot
        val settings = ui.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot
        assertTrue(newChat.left < settings.left)
        assertTrue(newChat.top >= footer.top)
        ui.onNodeWithText("FlClash").assertIsDisplayed()
        ui.onNodeWithText("Sandbox").assertIsDisplayed()
        assertTrue(
            ui.onNodeWithText("Sandbox").fetchSemanticsNode().boundsInRoot.top >
                ui.onNodeWithText("FlClash").fetchSemanticsNode().boundsInRoot.top,
        )
        ui.onNodeWithText("工具箱").assertDoesNotExist()
        capture("085-sidebar-glass")
        ui.onNodeWithContentDescription("搜索内容").performClick()
        ui.onNodeWithTag("drawer-search-field").assertIsDisplayed()
        capture("085-sidebar-search-open")
        ui.onNodeWithContentDescription("关闭搜索").performClick()
        ui.onNodeWithText("界面微调").performTouchInput { longClick() }
        ui.onNodeWithTag("session-context-menu").assertIsDisplayed()
        ui.onNodeWithText("重命名").performClick()
        ui.onNodeWithText("重命名对话").assertIsDisplayed()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("Sandbox").performTouchInput { click() }
        ui.onNodeWithText("创建 Sandbox").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("外观").assertIsDisplayed()
        val page = ui.onNodeWithTag("settings-screen").fetchSemanticsNode().boundsInRoot
        val card = ui.onNodeWithTag("conversation-card").fetchSemanticsNode().boundsInRoot
        assertEquals(28f, page.top - card.top, 1f)
        assertEquals(24f, card.bottom - page.bottom, 1f)
        ui.runOnIdle {
            val bitmap = bitmap()
            val x = card.center.x.toInt()
            assertEquals(DshaSurface.page.toArgb(), bitmap.getPixel(x, (card.top + 8).toInt()))
            assertEquals(DshaSurface.page.toArgb(), bitmap.getPixel(x, (page.top + 2).toInt()))
            assertEquals(DshaSurface.page.toArgb(), bitmap.getPixel(x, (card.bottom - 8).toInt()))
            bitmap.recycle()
        }
        capture("085-settings")
        ui.onNodeWithText("外观").performClick()
        ui.onNodeWithText("触感反馈").performScrollTo().assertIsDisplayed()
        capture("085-appearance")
        ui.runOnIdle { AssistantState.petEnabled = petEnabled }
    }

    @Test
    fun settingsKeepEnvironmentAndExtensionsAfterRemovingTheToolbox() {
        AssistantState.sessions.clear()
        AssistantState.sessions.add(Conversation("preview"))
        AssistantState.selectedId = "preview"
        screen { SettingsScreen(onCreatePlugin = {}) {} }
        ui.onNodeWithText("工具与环境").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("FlClash").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Sandbox").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("扩展").performScrollTo().assertIsDisplayed()
        capture("085-settings-tools")
        ui.onNodeWithText("扩展").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("FlClash").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun workflowCardsPreserveDraftAndShowObservedVerificationSeparately() {
        val c =
            Conversation("workflow-preview").apply {
                draft = "保留用户尚未发送的要求"
                workMode = WorkMode.PLAN
                workPlan =
                    WorkPlan(
                        1,
                        "完善手机界面并检查交互",
                        listOf(
                            PlanStep("layout", "调整卡片与控件", "completed"),
                            PlanStep("interaction", "检查触摸与导航", "in_progress", note = "保留已有输入与任务状态"),
                            PlanStep("device", "完成真机验收", "pending"),
                        ),
                    )
            }
        AssistantState.tasks.clear()
        val task =
            PhoneTask(
                    id = "workflow-preview-task",
                    sessionId = c.id,
                    text = "完善手机界面并检查交互",
                    images = emptyList(),
                    model = "deepseek-flash",
                    effort = "low",
                    access = "ask",
                    phoneTools = false,
                    fileTools = false,
                    workspace = "",
                    workMode = WorkMode.PLAN,
                )
                .apply {
                    status = "interrupted"
                    workPlan = c.workPlan
                    verifications.add(
                        VerificationRecord(
                            "针对性检查",
                            "",
                            "./gradlew testDebugUnitTest",
                            "passed",
                            0,
                            "测试示例：命令退出码为 0",
                            1L,
                        ),
                    )
                    verifications.add(
                        VerificationRecord(
                            "设备验收",
                            "",
                            "./gradlew connectedDebugAndroidTest",
                            "cancelled",
                            null,
                            "测试示例：已取消，未得出验收结果",
                            2L,
                        ),
                    )
                }
        AssistantState.tasks.add(task)
        screen { WorkflowWorkspace(c) }
        capture("085-workflow-plan-fixture")
        ui.onNodeWithTag("workflow-review").performClick().assertIsSelected()
        ui.onNodeWithTag("workflow-workspace").performScrollToNode(hasText("针对性检查"))
        ui.onNodeWithText("针对性检查").performClick()
        ui.onNodeWithText("测试示例：命令退出码为 0").assertIsDisplayed()
        capture("085-workflow-verification-fixture")
        ui.onNodeWithTag("workflow-workspace").performScrollToNode(hasTestTag("workflow-continue"))
        ui.onNodeWithTag("workflow-continue").performClick()
        ui.runOnIdle {
            assertEquals(WorkMode.EXECUTE, c.workMode)
            assertEquals("保留用户尚未发送的要求", c.draft)
            assertEquals(1, AssistantState.tasks.size)
            AssistantState.tasks.clear()
        }
    }

    @Test
    fun proxyDashboardUsesRealControlsAndDoesNotAllowStartWithoutAProfile() {
        var starts = 0
        screen {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Mihomo 代理", style = MaterialTheme.typography.headlineSmall)
                DshaSegments(listOf("概览", "配置", "节点", "规则", "连接", "日志").map { it to it }, "概览", {})
                MihomoDashboard(
                    MihomoState(),
                    null,
                    false,
                    "",
                    null,
                    null,
                    emptyList(),
                    { starts++ },
                    {},
                    {},
                    {},
                )
            }
        }
        ui.onNodeWithTag("proxy-power").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, starts)
        capture("085-proxy-empty")
    }

    @Test
    fun dashboardAndNodesRenderWithMeasuredDataAndMeasureDoesNotSelectNode() {
        var selections = 0
        var measurements = 0
        screen {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MihomoDashboard(
                    MihomoState(running = true),
                    "工作网络",
                    false,
                    "rule",
                    8,
                    ProxyTrafficSample(2000, 1240000, 28000000),
                    listOf(
                        ProxyTrafficRate(1400f, 60000f),
                        ProxyTrafficRate(9000f, 34000f),
                        ProxyTrafficRate(6000f, 78000f),
                        ProxyTrafficRate(4800f, 66000f),
                    ),
                    {},
                    {},
                    {},
                    {},
                )
                MihomoNodeCard(
                    "工作节点",
                    "Shadowsocks",
                    false,
                    42,
                    true,
                    true,
                    onSelect = { selections++ },
                    onMeasure = { measurements++ },
                )
            }
        }
        capture("085-proxy-fixture")
        ui.onNodeWithText("42 ms").performScrollTo().performClick()
        assertEquals(1, measurements)
        assertEquals(0, selections)
        ui.onNodeWithText("工作节点").performClick()
        assertEquals(1, selections)
    }

    @Test
    fun headerGradientFadesToTheActualContentAndGlassShaderCompiles() {
        ui.setContent {
            Box(Modifier.size(200.dp, 100.dp).background(Color.Magenta).testTag("fade-test")) {
                HeaderFade(Modifier.matchParentSize())
            }
        }
        val bounds = ui.onNodeWithTag("fade-test").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {
            val bitmap = bitmap()
            val top = bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + 4).toInt())
            val bottom = bitmap.getPixel(bounds.center.x.toInt(), (bounds.bottom - 3).toInt())
            assertTrue(android.graphics.Color.green(top) > 245)
            assertTrue(android.graphics.Color.green(bottom) < 15)
            bitmap.recycle()
            val lens = GlassLens()
            lens.resize(200f, 48f, 24f)
            assertNotNull(lens.effect)
        }
    }

    @Test
    fun sheetCloseButtonStaysVisibleWhenItsContentScrolls() {
        var closed by mutableStateOf(false)
        screen {
            if (!closed)
                WhaleSheet("选项", close = { closed = true }) { repeat(30) { Text("内容 $it") } }
        }
        val close = ui.onNodeWithContentDescription("关闭面板")
        val before = close.fetchSemanticsNode().boundsInRoot
        ui.onNodeWithText("内容 29").performScrollTo()
        assertEquals(before, close.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        close.performClick()
        ui.runOnIdle { assertTrue(closed) }
    }
}
