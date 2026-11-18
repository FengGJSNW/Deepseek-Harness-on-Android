package dev.deepseek.mobile

import android.app.Application
import dev.deepseek.mobile.workflow.WorkMode
import dev.deepseek.mobile.workflow.WorkflowPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServiceStartupTest {
    @Test
    fun frameworkCanConstructServiceBeforeAttachingItsContext() {
        // Android instantiates a Service with a null base context before calling attach/onCreate.
        // This is deliberately a real Service constructor, not a mocked policy or Context.
        val service = AgentService()
        assertNull(service.baseContext)
    }

    @Test
    fun attachedServiceLoadsPackagedPolicyBeforeClassifyingToolRequests() {
        val service = Robolectric.buildService(AgentService::class.java).get()
        assertNotNull(service.baseContext)
        val policy =
            AgentService::class
                .java
                .getDeclaredMethod("getWorkflowPolicy")
                .apply { isAccessible = true }
                .invoke(service) as WorkflowPolicy
        val methods =
            AgentService::class
                .java
                .getDeclaredMethod("getTaskMethods")
                .apply { isAccessible = true }
                .invoke(service) as Set<*>
        assertTrue(methods.containsAll(listOf("work_plan", "verification_run", "workspace_edit")))
        assertFalse(policy.permits(WorkMode.PLAN, "workspace_write"))
        assertTrue(policy.permits(WorkMode.PLAN, "workspace_read"))
    }
}
