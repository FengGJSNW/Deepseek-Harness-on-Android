package dev.deepseek.mobile

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.deepseek.mobile.workflow.PlanStep
import dev.deepseek.mobile.workflow.WorkMode
import dev.deepseek.mobile.workflow.WorkPlan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkflowUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun continuingPlanPreservesExistingDraftAndDoesNotSubmit() {
        ui.waitUntil(30000) { AssistantState.ready }
        val c =
            Conversation().apply {
                workMode = WorkMode.PLAN
                workPlan = WorkPlan(1, "修复并验证", listOf(PlanStep("test", "运行针对性检查", "pending")))
                draft = "保留用户未发送的修改要求"
            }
        var count = 0
        ui.runOnUiThread {
            count = AssistantState.tasks.size
            ui.activity.setContent { MaterialTheme { WorkflowWorkspace(c) } }
        }
        ui.onNodeWithTag("workflow-review").performClick().assertIsSelected()
        ui.onNodeWithTag("workflow-continue").performScrollTo().performClick()
        ui.runOnIdle {
            assertEquals(WorkMode.EXECUTE, c.workMode)
            assertEquals("保留用户未发送的修改要求", c.draft)
            assertEquals(count, AssistantState.tasks.size)
            c.draft = ""
            c.workMode = WorkMode.PLAN
        }
        ui.onNodeWithTag("workflow-continue").performScrollTo().performClick()
        ui.runOnIdle {
            assertTrue(c.draft.contains("修复并验证"))
            assertEquals(count, AssistantState.tasks.size)
        }
    }
}
