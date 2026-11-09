package dev.deepseek.mobile

import android.os.Process
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.deepseek.mobile.harness.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HarnessBridgeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun singleAppElevationWaitsForHumanAndDoesNotChangeSessionEnvironment() {
        ui.waitUntil(30000) { AssistantState.ready && AgentService.current != null }
        val service = AgentService.current!!
        val permissions = HarnessCoordinator.get(ui.activity).permissions
        val savedRules = permissions.export()
        // Model transport is outside this native permission test. Simulate the lifecycle latch
        // normally set by pump(), without adding a production test API or making a paid request.
        val activeField =
            AgentService::class.java.getDeclaredField("active").apply { isAccessible = true }
        val wasActive = activeField.getBoolean(service)
        lateinit var task: PhoneTask
        var result: JSONObject? = null
        var finished = false
        try {
            ui.runOnUiThread {
                resetTransientUiForTest()
                val chat = AssistantState.addSession()
                task =
                    PhoneTask(
                        sessionId = chat.id,
                        text = "仅在模拟器检查应用 UID",
                        images = emptyList(),
                        model = "deepseek-flash",
                        effort = "low",
                        access = "full",
                        phoneTools = false,
                        fileTools = true,
                        workspace = "",
                        terminalEnabled = true,
                        terminalConfig = service.terminal.config.load("sandbox").json().toString(),
                    )
                activeField.setBoolean(service, true)
                task.status = "running"
                AssistantState.tasks.add(task)
                AssistantState.activeTaskId = task.id
                service.perform(
                    "terminal_elevate",
                    JSONObject().put("level", "app").put("command", "id -u"),
                ) { value ->
                    result = value as? JSONObject
                    finished = true
                }
            }
            ui.waitUntil(10000) { task.approval }
            assertFalse(finished)
            assertTrue(task.question.contains("id -u"))
            assertTrue(service.terminal.runs.values.none { it.taskId == task.id })
            ui.runOnUiThread { service.answer("允许", true) }
            ui.waitUntil(30000) { finished }
            assertNotNull(result)
            assertEquals(0, result!!.getInt("exitCode"))
            assertEquals(Process.myUid().toString(), result!!.getString("stdout").trim())
            assertTrue(result!!.getBoolean("singleOperationElevation"))
            assertEquals("sandbox", JSONObject(task.terminalConfig).getString("level"))
            assertEquals("sandbox", AssistantState.current.terminalLevel)

            // This is a denial-path test. It must not connect to su, request OS authorization,
            // create a Root run, or execute any Root command, even in this emulator.
            permissions.saveFromUser(
                JSONArray(
                        listOf(
                            PermissionRule(
                                    scope = PermissionScope.GLOBAL,
                                    capability = Capability.ROOT_EXEC,
                                    effect = PermissionEffect.DENY,
                                )
                                .json(),
                        ),
                    )
                    .toString(),
            )
            finished = false
            ui.runOnUiThread {
                service.perform(
                    "terminal_elevate",
                    JSONObject().put("level", "root").put("command", "id -u"),
                ) { value ->
                    result = value as? JSONObject
                    finished = true
                }
            }
            ui.waitUntil(10000) { finished }
            assertNull(result)
            assertFalse(task.approval)
            assertTrue(
                service.terminal.runs.values.none { it.taskId == task.id && it.level == "root" },
            )
        } finally {
            activeField.setBoolean(service, wasActive)
            permissions.saveFromUser(savedRules)
            ui.runOnUiThread {
                runCatching {
                    task.status = "cancelled"
                    AssistantState.tasks.remove(task)
                }
                AssistantState.activeTaskId = ""
            }
        }
    }
}
