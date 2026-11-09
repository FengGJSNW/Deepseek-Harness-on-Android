package dev.deepseek.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.deepseek.mobile.harness.HarnessCoordinator
import dev.deepseek.mobile.harness.PluginStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PluginCreatorTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensCreatorConversationFromExtensions() {
        ui.waitUntil(30000) { AssistantState.ready }
        ui.runOnUiThread {
            resetTransientUiForTest()
            AssistantState.appMode = "work"
            AssistantState.activeTaskId = ""
            AssistantState.addSession()
        }
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("扩展").performScrollTo().performClick()
        ui.onNodeWithText("让 Agent 创建插件").assertIsDisplayed().performClick()
        ui.onNodeWithText("Creator · 插件创作").assertIsDisplayed()
        ui.onNodeWithText("描述你想制作的插件").assertExists()
        ui.runOnUiThread { assertTrue(AssistantState.current.creatorMode) }
    }

    @Test
    fun approvesInstallsRunsAndDisablesActualSandboxedCode() = runBlocking {
        val context = ui.activity.applicationContext
        val manager = HarnessCoordinator.get(context).plugins
        val id = "test-" + UUID.randomUUID().toString().take(8)
        val task =
            PhoneTask(
                sessionId = "creator-test",
                text = "测试插件",
                images = emptyList(),
                model = "deepseek-flash",
                effort = "low",
                access = "ask",
                phoneTools = false,
                fileTools = false,
                workspace = "",
                creatorMode = true,
            )
        fun manifest(code: String) =
            JSONObject()
                .put("id", id)
                .put("name", "测试插件")
                .put("version", "1.0.0")
                .put("description", "测试实际离线计算")
                .put(
                    "tools",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "calculate")
                                .put("description", "接收数字 n")
                                .put("code", code),
                        ),
                )
        fun install(code: String) =
            JSONObject().put("action", "install_bundle").put("bundle", manifest(code))
        val code =
            "return {doubled: input.n * 2, network: typeof fetch, document: typeof document};"
        val call =
            JSONObject()
                .put("plugin_id", id)
                .put("tool", "calculate")
                .put("arguments", JSONObject().put("n", 21))
        try {
            assertTrue(
                runCatching {
                    manager.manage(install(code), task, {}) { _, _ -> error("拒绝") }
                }
                    .isFailure,
            )
            assertNull(manager.store.find(id))
            var approvals = 0
            assertTrue(
                manager
                    .manage(install(code), task, {}) { _, _ -> approvals++ }
                    .getBoolean("applied"),
            )
            assertEquals(1, approvals)
            val installed = requireNotNull(PluginStore(context).find(id))
            assertEquals(code, installed.bundle.tools.single().code)
            val result =
                manager
                    .call(call, task.sessionId, "", ask = { _, _ -> error("不应再次询问") })
                    .getJSONObject("result")
            assertEquals(42, result.getInt("doubled"))
            assertEquals("undefined", result.getString("network"))
            assertEquals("undefined", result.getString("document"))
            assertTrue(
                runCatching {
                    manager.manage(install("return {"), task, {}) { _, _ -> }
                }
                    .isFailure,
            )
            assertEquals(installed.revision, manager.store.find(id)?.revision)
            manager.store.setEnabled(installed, false)
            assertTrue(
                runCatching {
                    manager.call(call, task.sessionId, "", ask = { _, _ -> })
                }
                    .isFailure,
            )
        } finally {
            manager.store.find(id)?.let { manager.store.remove(it) }
        }
    }
}
