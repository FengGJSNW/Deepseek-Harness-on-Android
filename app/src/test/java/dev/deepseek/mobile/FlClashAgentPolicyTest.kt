package dev.deepseek.mobile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.deepseek.mobile.proxy.FlClashAgentPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FlClashAgentPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun freshInstallDeniesAgentOperationsAndUnknownActions() {
        val policy = FlClashAgentPolicy(context)
        for (action in
            listOf("status", "start", "stop", "select", "rule", "setPolicy", "unknown")) {
            assertThrows(IllegalStateException::class.java) { policy.require(action) }
        }
    }

    @Test
    fun permissionsAreIndependentAndRevocationAppliesImmediately() {
        val policy = FlClashAgentPolicy(context)
        policy.set("nodes", true)
        policy.require("select")
        policy.require("status")
        assertThrows(IllegalStateException::class.java) { policy.require("start") }
        assertThrows(IllegalStateException::class.java) { policy.require("rule") }
        policy.set("nodes", false)
        assertThrows(IllegalStateException::class.java) { policy.require("select") }
        assertFalse(FlClashAgentPolicy(context).state.value.enabled)
    }

    @Test
    fun legacyPermissionsMigrateOnceWithoutRestoringRevokedConsent() {
        context
            .getSharedPreferences("mihomo", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("agentStart", true)
            .commit()
        val policy = FlClashAgentPolicy(context)
        policy.require("start")
        assertFalse(policy.state.value.nodes)
        policy.set("start", false)
        assertFalse(FlClashAgentPolicy(context).state.value.start)
    }
}
