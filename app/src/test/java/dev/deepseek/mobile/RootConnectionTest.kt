package dev.deepseek.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.deepseek.mobile.terminal.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RootConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val binding = ControlledRootBinding()
    private val connections = TerminalConnections(context, binding)

    @After fun close() = connections.close()

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Root state: ${connections.rootState}", condition())
    }

    @Test
    fun delayedGrantActivatesWithoutReselectingTheTier() {
        connections.connectRoot()
        await { binding.attempts == 1 }
        assertFalse(connections.rootState.ready)
        binding.grant.complete(Unit)
        await { binding.connected != null }
        binding.reportUid(0)
        await { connections.rootState.ready }
        assertEquals(0, connections.rootState.uid)
        assertEquals(1, binding.attempts)
    }

    @Test
    fun foregroundRefreshRetriesARequestedFailedConnection() {
        binding.failure = IllegalStateException("transport not ready")
        connections.connectRoot()
        await { connections.rootState.phase == "failed" }
        binding.failure = null
        connections.refresh()
        binding.grant.complete(Unit)
        await { binding.connected != null }
        binding.reportUid(0)
        await { connections.rootState.ready }
        assertEquals(2, binding.attempts)
    }

    @Test
    fun denialDoesNotTurnIntoAnAutomaticPromptLoop() {
        binding.failure = RootNotGranted()
        connections.connectRoot()
        await { connections.rootState.phase == "denied" }
        repeat(3) { connections.refresh() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, binding.attempts)
        assertFalse(connections.rootState.ready)
    }

    @Test
    fun selectedRootIsRestoredWhenServiceArrivesAfterTheUiSelection() {
        connections.restoreSelected(TerminalLevel.ROOT, false)
        connections.restoreSelected(TerminalLevel.APP, true)
        assertEquals(0, binding.attempts)
        connections.restoreSelected(TerminalLevel.ROOT, true)
        await { binding.attempts == 1 }
    }

    @Test
    fun cancelledBindingCannotRestoreRootThroughALateCallback() {
        binding.grant.complete(Unit)
        connections.connectRoot()
        await { binding.connected != null }
        connections.disconnect(true)
        binding.reportUid(0)
        connections.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(connections.rootState.ready)
        assertEquals(1, binding.attempts)
        assertEquals(1, binding.unbinds)
    }

    @Test
    fun grantedSuWithoutUidZeroNeverBecomesReady() {
        binding.grant.complete(Unit)
        connections.connectRoot()
        await { binding.connected != null }
        binding.reportUid(2000)
        await { connections.rootState.phase == "failed" }
        assertFalse(connections.rootState.ready)
        assertEquals("IDENTITY_VERIFICATION_FAILED", connections.rootState.errorCode)
    }

    private inner class ControlledRootBinding : RootBinding {
        var attempts = 0
        var unbinds = 0
        var failure: Exception? = null
        val grant = CompletableDeferred<Unit>()
        var connected: ServiceConnection? = null

        override suspend fun bind(authorized: () -> ServiceConnection?) {
            attempts++
            failure?.let { throw it }
            grant.await()
            connected = authorized()
        }

        override fun unbind(connection: ServiceConnection) {
            unbinds++
        }

        fun reportUid(uid: Int) {
            val binder = Binder()
            val worker =
                Proxy.newProxyInstance(
                    ITerminalWorker::class.java.classLoader,
                    arrayOf(ITerminalWorker::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "asBinder" -> binder
                        "info" -> "{\"uid\":$uid,\"pid\":1234,\"guard_prerequisites\":false}"
                        else -> null
                    }
                } as ITerminalWorker
            binder.attachInterface(worker, "dev.deepseek.mobile.terminal.ITerminalWorker")
            connected!!.onServiceConnected(
                ComponentName(context, RootTerminalService::class.java),
                binder,
            )
        }
    }
}
