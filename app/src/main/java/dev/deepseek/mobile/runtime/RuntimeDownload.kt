package dev.deepseek.mobile.runtime

import android.content.Context
import dev.deepseek.mobile.proxy.FlClashRuntime
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object RuntimeDownload {
    class Prompt(val answer: CompletableDeferred<Boolean> = CompletableDeferred())

    private val waiting = MutableStateFlow<Prompt?>(null)
    val prompt = waiting.asStateFlow()
    private val asking = AtomicBoolean(false)
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()

    fun dismissError() {
        _error.value = ""
    }

    suspend fun <T> transfer(context: Context, block: suspend (Int?) -> T): T = coroutineScope {
        val proxy = FlClashRuntime.get(context)
        val before = proxy.terminalPort()
        val monitor = launch {
            delay(8000)
            if (before != null || !asking.compareAndSet(false, true)) return@launch
            var offer: Prompt? = null
            try {
                val status = proxy.user("status")
                if (status.optBoolean("running") || !status.optBoolean("hasProfile")) return@launch
                offer = Prompt()
                waiting.value = offer
                if (offer.answer.await()) proxy.user("start")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "代理启动失败，可在 FlClash 中检查"
            } finally {
                if (waiting.value === offer) waiting.value = null
                asking.set(false)
            }
        }
        try {
            try {
                block(before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = proxy.terminalPort()
                if (now != null && now != before) block(now) else throw e
            }
        } finally {
            monitor.cancelAndJoin()
        }
    }

    fun connection(url: String, port: Int?) =
        if (port == null) URL(url).openConnection()
        else URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
}
