package dev.deepseek.mobile

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WebViewRecoveryTest {
    private class RecordingPage(context: Context) : WebView(context) {
        lateinit var bridge: AgentService.Bridge
        val received = mutableListOf<String>()
        var scriptResult: String? = null
        var disposed = false

        override fun addJavascriptInterface(obj: Any, name: String) {
            if (name == "Android") bridge = obj as AgentService.Bridge
            super.addJavascriptInterface(obj, name)
        }

        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            received += script
            scriptResult?.let { callback?.onReceiveValue(it) }
        }

        override fun destroy() {
            disposed = true
            super.destroy()
        }
    }

    @Test
    fun missingBootstrapReportedEvenIfItsOwnErrorHandlerCouldNotLoad() {
        val host = Robolectric.buildService(AgentService::class.java).get()
        val page = RecordingPage(host)
        host.loadEngine { page }
        page.scriptResult = "false"
        page.webViewClient.onPageFinished(page, "${AgentService.ORIGIN}/assets/index.html")
        assertTrue(AssistantState.engineFailure.contains("脚本"))
        page.bridge.ui("ready", "true")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AssistantState.ready)
    }

    @Test
    fun missingProviderLeavesHostAvailableForExplicitRetry() {
        val host = Robolectric.buildService(AgentService::class.java).get()
        host.loadEngine { throw android.util.AndroidRuntimeException("provider is updating") }
        assertFalse(AssistantState.ready)
        assertTrue(AssistantState.error.contains("WebView"))
        val page = RecordingPage(host)
        host.loadEngine { page }
        assertSame(page, host.webView)
        assertEquals("", AssistantState.error)
    }

    @Test
    fun rendererCrashDoesNotSpinOrDeliverRetiredBridgeResponsesToReplacement() {
        val host = Robolectric.buildService(AgentService::class.java).get()
        val first = RecordingPage(host)
        host.loadEngine { first }
        val client = first.webViewClient
        val crash =
            object : RenderProcessGoneDetail() {
                override fun didCrash() = true

                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
            }
        // A previously queued UI callback and a native rejection both outlive this renderer.
        first.bridge.ui("error", "\"stale\"")
        val reusedId = "r".repeat(101)
        first.bridge.request(reusedId, "status", "{}")
        assertTrue(client.onRenderProcessGone(first, crash))
        assertTrue(first.disposed)
        assertFalse(AssistantState.ready)
        assertTrue(AssistantState.error.contains("崩溃"))
        val next = RecordingPage(host)
        host.loadEngine { next }
        // A late renderer notification must not discard the replacement a second time.
        client.onRenderProcessGone(first, crash)
        first.bridge.request(reusedId, "status", "{}")
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(next, host.webView)
        assertFalse(next.disposed)
        assertEquals("", AssistantState.error)
        assertTrue(next.received.isEmpty())
        next.bridge.request(reusedId, "status", "{}")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(next.received.single().contains(reusedId))
    }
}
