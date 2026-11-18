package dev.deepseek.mobile.proxy

import android.content.Context
import android.content.Intent
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.follow.clash.EmbeddedEngine
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class FlClashRuntime private constructor(private val context: Context) {
    val policy = FlClashAgentPolicy(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<Unit>()
    private val serial = Mutex()
    private val userRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val requests = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Job>>()
    private lateinit var channel: MethodChannel
    @Volatile private var activePort: Int? = null
    private var configuredPort: Int? = null
    private var routeRequested = false
    private var routeRevision = 0
    private val routes = Mutex()
    private val migration = context.getSharedPreferences("flclash-migration", Context.MODE_PRIVATE)

    fun attach(engine: FlutterEngine) {
        EmbeddedEngine.checkAgentRequest = ::checkRequest
        scope.launch {
            com.follow.clash.ServiceState.runState.collect {
                routes.withLock { refreshRoute() }
            }
        }
        channel =
            MethodChannel(engine.dartExecutor.binaryMessenger, "dev.deepseek.mobile/flclash_agent")
        channel.setMethodCallHandler { call, result ->
            scope.launch {
                try {
                    val value: Any? =
                        when (call.method) {
                            "prepareStart" -> {
                                val request = requireNotNull(call.argument<String>("requestId"))
                                checkRequest(request)
                                if (
                                    call.argument<Boolean>("vpn") == true &&
                                        android.net.VpnService.prepare(context) != null
                                ) {
                                    val activity =
                                        dev.deepseek.mobile.MainActivity.current
                                            ?: error("需要 VPN 系统授权，请打开 DSHA 或 FlClash 完成授权")
                                    check(activity.authorizeProxyVpn()) {
                                        "VPN 系统授权未通过，代理未启动"
                                    }
                                }
                                checkRequest(request)
                                true
                            }
                            "bootstrap" -> {
                                MihomoManager.get(context).stop()
                                null
                            }
                            "ready" -> {
                                ready.complete(Unit)
                                null
                            }
                            "route" -> {
                                val revision = requireNotNull(call.argument<Int>("revision"))
                                routes.withLock {
                                    if (revision > routeRevision) {
                                        routeRevision = revision
                                        val port = requireNotNull(call.argument<Int>("port"))
                                        configuredPort = port.takeIf { it in 1..65535 }
                                        routeRequested = call.argument<Boolean>("running") == true
                                        refreshRoute()
                                    }
                                }
                                null
                            }
                            "policy" -> policy.state.value.json().toString()
                            "setPolicy" ->
                                withContext(Dispatchers.IO) {
                                    policy
                                        .set(
                                            requireNotNull(call.argument<String>("key")),
                                            requireNotNull(call.argument<Boolean>("value")),
                                        )
                                        .json()
                                        .toString()
                                }
                            "check" -> {
                                val id = call.arguments as? String ?: error("缺少请求编号")
                                checkRequest(id)
                                true
                            }
                            "legacyProfiles" ->
                                withContext(Dispatchers.IO) {
                                    val profiles = MihomoProfiles(context).list()
                                    val selected = MihomoManager.get(context).state.value.profileId
                                    JSONArray(
                                            profiles
                                                .filterNot { migration.contains(it.id) }
                                                .map {
                                                    JSONObject()
                                                        .put("id", it.id)
                                                        .put("name", it.name)
                                                        .put(
                                                            "yaml",
                                                            dev.deepseek.mobile.harness.SafeYaml
                                                                .write(MihomoProfiles.config(it)),
                                                        )
                                                        .put("source", it.source)
                                                        .put("selected", it.id == selected)
                                                        .put("nodes", JSONObject(it.selectedNodes))
                                                },
                                        )
                                        .toString()
                                }
                            "migrated" ->
                                withContext(Dispatchers.IO) {
                                    check(
                                        migration
                                            .edit()
                                            .putBoolean(call.arguments as String, true)
                                            .commit(),
                                    )
                                    null
                                }
                            else -> {
                                result.notImplemented()
                                return@launch
                            }
                        }
                    result.success(value)
                } catch (error: Exception) {
                    result.error("flclash_host", error.message, null)
                }
            }
        }
    }

    fun open() {
        val activity = dev.deepseek.mobile.MainActivity.current?.takeUnless { it.isFinishing }
        val host = activity ?: context
        val intent = Intent(host, com.follow.clash.MainActivity::class.java)
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host.startActivity(
            intent,
            android.app.ActivityOptions.makeCustomAnimation(
                    host,
                    dev.deepseek.mobile.R.anim.dsha_scene_enter,
                    dev.deepseek.mobile.R.anim.dsha_scene_exit,
                )
                .toBundle(),
        )
    }

    fun terminalPort(): Int? = activePort

    private suspend fun refreshRoute() {
        val port = configuredPort.takeIf {
            routeRequested && com.follow.clash.ServiceState.awaitRunTime() > 0L
        }
        if (activePort == port) return
        activePort = port
        applyBrowserProxy(port)
    }

    private suspend fun applyBrowserProxy(port: Int?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        suspendCancellableCoroutine<Unit> { completion ->
            val applied = Runnable { if (completion.isActive) completion.resume(Unit) {} }
            val controller = ProxyController.getInstance()
            if (port == null) controller.clearProxyOverride(context.mainExecutor, applied)
            else
                controller.setProxyOverride(
                    ProxyConfig.Builder()
                        .addProxyRule("http://127.0.0.1:$port")
                        .addBypassRule("appassets.androidplatform.net")
                        .build(),
                    context.mainExecutor,
                    applied,
                )
        }
    }

    private fun checkRequest(id: String) {
        val request = requests[id] ?: error("请求已取消")
        check(request.second.isActive) { "请求已取消" }
        if (id !in userRequests) policy.require(request.first)
    }

    suspend fun agent(action: String, args: JSONObject): JSONObject = command(action, args, false)

    /** Called only after an explicit native user action; never exposed as an Agent tool. */
    suspend fun user(action: String): JSONObject {
        require(action in setOf("status", "start"))
        return command(action, JSONObject(), true)
    }

    private suspend fun command(action: String, args: JSONObject, user: Boolean): JSONObject =
        serial.withLock {
            if (!user) policy.require(action)
            withContext(Dispatchers.Main.immediate) {
                withTimeout(90_000) {
                    EmbeddedEngine.get(context)
                    ready.await()
                    if (!user) policy.require(action)
                    val id = UUID.randomUUID().toString()
                    val response = CompletableDeferred<String>()
                    requests[id] = action to currentCoroutineContext().job
                    if (user) userRequests += id
                    try {
                        channel.invokeMethod(
                            "command",
                            JSONObject(args.toString())
                                .put("requestId", id)
                                .put("action", action)
                                .toString(),
                            object : MethodChannel.Result {
                                override fun success(result: Any?) {
                                    if (response.isActive)
                                        response.complete(result as? String ?: "{}")
                                }

                                override fun error(code: String, message: String?, details: Any?) {
                                    response.completeExceptionally(
                                        IllegalStateException(message ?: code),
                                    )
                                }

                                override fun notImplemented() {
                                    response.completeExceptionally(
                                        IllegalStateException("FlClash 接口未就绪"),
                                    )
                                }
                            },
                        )
                        JSONObject(response.await())
                    } finally {
                        requests.remove(id)
                        userRequests -= id
                        response.cancel()
                    }
                }
            }
        }

    companion object {
        @Volatile private var instance: FlClashRuntime? = null

        fun get(context: Context): FlClashRuntime =
            instance
                ?: synchronized(this) {
                    instance ?: FlClashRuntime(context.applicationContext).also { instance = it }
                }
    }
}
