package dev.deepseek.mobile.proxy

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.deepseek.mobile.harness.SafeYaml
import dev.deepseek.mobile.harness.boundedBytes
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class MihomoState(
    val profileId: String = "",
    val running: Boolean = false,
    val browser: Boolean = false,
    val terminal: Boolean = false,
    val agentNodes: Boolean = false,
    val agentRules: Boolean = false,
    val autoUpdate: Boolean = false,
)

internal class MihomoManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("mihomo", Context.MODE_PRIVATE)
    private val home = File(context.filesDir, "mihomo/core").apply { mkdirs() }
    private val core = File(context.applicationInfo.nativeLibraryDir, "libdsha_mihomo.so")
    private val profiles = MihomoProfiles(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messages = ArrayDeque<String>()
    private val mutable =
        MutableStateFlow(
            MihomoState(
                prefs.getString("profile", "").orEmpty(),
                browser = prefs.getBoolean("browser", true),
                terminal = prefs.getBoolean("terminal", true),
                agentNodes = prefs.getBoolean("agentNodes", false),
                agentRules = prefs.getBoolean("agentRules", false),
                autoUpdate = prefs.getBoolean("autoUpdate", false),
            ),
        )
    val state = mutable.asStateFlow()

    private data class Core(
        val process: Process,
        val port: Int,
        val controller: Int,
        val token: String,
    )

    @Volatile private var active: Core? = null
    private var browserApplied = false

    suspend fun list(): List<ProxyProfile> = locked { profiles.list() }

    suspend fun import(name: String, yaml: String, source: String = ""): ProxyProfile = locked {
        check(profiles.list().size < 40) { "最多保存 40 个代理配置" }
        val profile =
            ProxyProfile(
                name = name.trim(),
                yaml = if (source.isBlank()) yaml else MihomoProfiles.download(source),
                source = source,
            )
        profiles.save(profile)
        if (mutable.value.profileId.isBlank()) select(profile.id)
        profile
    }

    suspend fun edit(id: String, yaml: String) = locked {
        applyProfile(profiles.read(id).copy(yaml = yaml))
    }

    suspend fun refresh(id: String) = locked { refreshProfile(id) }

    private fun refreshProfile(id: String) {
        val profile = profiles.read(id)
        check(profile.source.isNotBlank()) { "本地配置没有订阅地址" }
        applyProfile(
            profile.copy(
                yaml = MihomoProfiles.download(profile.source),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun remove(id: String) = locked {
        if (mutable.value.profileId == id) {
            stopCore()
            select("")
        }
        profiles.delete(id)
    }

    suspend fun activate(id: String) = locked {
        val profile = profiles.read(id)
        val core = active
        if (core?.process?.isAlive == true) reload(core, profile) else if (core != null) stopCore()
        select(id)
    }

    private fun select(id: String) {
        prefs.edit().putString("profile", id).apply()
        mutable.value = mutable.value.copy(profileId = id)
    }

    suspend fun start() = locked {
        if (active?.process?.isAlive == true) return@locked
        check(core.canExecute()) { "此安装包没有适用的 Mihomo 内核" }
        val profile = profiles.read(mutable.value.profileId.ifBlank { error("请先导入并选择代理配置") })
        stopCore()
        val port = freePort()
        var controller = freePort()
        while (controller == port) controller = freePort()
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val config = File(home, "running.yaml")
        config.writeText(render(profile, port, controller, token))
        val process =
            ProcessBuilder(core.absolutePath, "-d", home.absolutePath, "-f", config.absolutePath)
                .directory(home)
                .redirectErrorStream(true)
                .start()
        val launched = Core(process, port, controller, token)
        synchronized(messages) { messages.clear() }
        scope.launch {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::log) }
            }
        }
        try {
            withTimeout(16000) {
                while (true) {
                    check(process.isAlive) { "内核启动失败，请查看日志" }
                    val ready = runCatching {
                        check(api(launched, "/version", timeout = 500).has("version"))
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                    }
                        .isSuccess
                    if (ready) break
                    delay(150)
                }
            }
            if (mutable.value.browser) browserProxy(port)
            active = launched
            mutable.value = mutable.value.copy(running = true)
            scope.launch {
                process.waitFor()
                locked {
                    if (active == launched) {
                        mutable.value = mutable.value.copy(running = false)
                        log("代理内核已退出")
                    }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { if (browserApplied) browserProxy(null) }
                terminate(process)
            }
            throw error
        }
    }

    suspend fun stop() = locked { stopCore() }

    private suspend fun stopCore() {
        if (browserApplied) browserProxy(null)
        active?.let { terminate(it.process) }
        active = null
        mutable.value = mutable.value.copy(running = false)
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    suspend fun toggle(key: String, value: Boolean) = locked {
        val previous = mutable.value
        val updated =
            when (key) {
                "browser" -> {
                    if (active != null)
                        browserProxy(if (value) requireNotNull(active).port else null)
                    previous.copy(browser = value)
                }
                "terminal" -> previous.copy(terminal = value)
                "agentNodes" -> previous.copy(agentNodes = value)
                "agentRules" -> previous.copy(agentRules = value)
                "autoUpdate" -> previous.copy(autoUpdate = value)
                else -> error("设置无效")
            }
        prefs.edit().putBoolean(key, value).apply()
        mutable.value = updated
    }

    init {
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (!mutable.value.autoUpdate || !mutable.value.running) continue
                try {
                    locked {
                        val profile = profiles.read(mutable.value.profileId)
                        if (
                            profile.source.isNotBlank() &&
                                System.currentTimeMillis() - profile.updatedAt > 86_400_000
                        ) {
                            refreshProfile(profile.id)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    log("订阅自动更新失败，保留当前配置")
                    delay(3_600_000)
                }
            }
        }
    }

    private suspend fun browserProxy(port: Int?) =
        withContext(Dispatchers.Main.immediate) {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                "当前系统 WebView 不支持代理，请升级或关闭网页代理"
            }
            withContext(NonCancellable) {
                suspendCancellableCoroutine { done ->
                    val executor = context.mainExecutor
                    if (port == null)
                        ProxyController.getInstance().clearProxyOverride(executor) {
                            done.resume(Unit)
                        }
                    else
                        ProxyController.getInstance().setProxyOverride(
                            ProxyConfig.Builder()
                                .addProxyRule("http://127.0.0.1:$port")
                                .addBypassRule("appassets.androidplatform.net")
                                .build(),
                            executor,
                        ) {
                            done.resume(Unit)
                        }
                }
                browserApplied = port != null
            }
        }

    fun terminalPort(): Int? = active?.port?.takeIf { state.value.terminal }

    fun logs(): List<String> = synchronized(messages) { messages.toList() }

    private fun log(line: String) =
        synchronized(messages) {
            if (messages.size == 200) messages.removeFirst()
            messages.addLast(line.take(1600))
        }

    suspend fun data(path: String): JSONObject = locked {
        require(
            path in
                setOf(
                    "/version",
                    "/configs",
                    "/proxies",
                    "/rules",
                    "/connections",
                    "/providers/proxies",
                    "/providers/rules",
                ),
        )
        api(running(), path)
    }

    suspend fun mode(mode: String) = locked {
        require(mode in setOf("rule", "global", "direct"))
        val profile = profiles.read(mutable.value.profileId)
        val core = running()
        val previousMode = api(core, "/configs").getString("mode")
        api(core, "/configs", "PATCH", JSONObject().put("mode", mode))
        try {
            profiles.save(profile.copy(mode = mode))
        } catch (error: Exception) {
            runCatching { api(core, "/configs", "PATCH", JSONObject().put("mode", previousMode)) }
            throw error
        }
    }

    suspend fun choose(group: String, node: String) = locked { chooseNode(group, node) }

    private fun chooseNode(group: String, node: String) {
        val core = running()
        val item = api(core, "/proxies/${encode(group)}")
        check(item.optString("type").equals("Selector", ignoreCase = true)) { "请选择可手动切换的节点组" }
        val options = item.optJSONArray("all") ?: JSONArray()
        require((0 until options.length()).any { options.optString(it) == node }) { "节点不在此组中" }
        api(core, "/proxies/${encode(group)}", "PUT", JSONObject().put("name", node))
    }

    suspend fun delay(name: String): Int = locked {
        api(
                running(),
                "/proxies/${encode(name)}/delay?timeout=5000&url=${encode("https://www.gstatic.com/generate_204")}",
                timeout = 7000,
            )
            .getInt("delay")
    }

    suspend fun disconnect(id: String? = null) = locked {
        api(running(), "/connections" + if (id == null) "" else "/${encode(id)}", "DELETE")
    }

    suspend fun updateProvider(kind: String, name: String) = locked {
        require(kind in setOf("proxies", "rules"))
        api(running(), "/providers/$kind/${encode(name)}", "PUT", timeout = 16000)
    }

    suspend fun setRule(type: String, domain: String, target: String) = locked {
        addRule(type, domain, target)
    }

    private fun addRule(type: String, domain: String, target: String) {
        require(type in setOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD")) { "规则类型不支持" }
        require(domain.length in 1..253 && domain.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9.-]*"))) {
            "域名或关键词无效"
        }
        require(target.length in 1..120 && target.none { it == ',' || it.isISOControl() }) {
            "规则目标无效"
        }
        check(api(running(), "/proxies").getJSONObject("proxies").has(target)) { "请使用现有节点或策略组" }
        val profile = profiles.read(mutable.value.profileId)
        val prefix = "$type,${domain.lowercase()},"
        val newRules = listOf(prefix + target) + profile.rules.filterNot { it.startsWith(prefix) }
        check(newRules.size <= 100) { "最多 100 条本地规则" }
        applyProfile(profile.copy(rules = newRules))
    }

    suspend fun removeRule(rule: String) = locked {
        val profile = profiles.read(mutable.value.profileId)
        applyProfile(profile.copy(rules = profile.rules - rule))
    }

    suspend fun agent(action: String, args: JSONObject): JSONObject = locked {
        val settings = mutable.value
        when (action) {
            "status" -> {
                check(settings.agentNodes || settings.agentRules) { "Agent 代理管理已关闭" }
                val proxies = api(running(), "/proxies").getJSONObject("proxies")
                val groups =
                    proxies
                        .keys()
                        .asSequence()
                        .take(300)
                        .mapNotNull { name ->
                            val item = proxies.getJSONObject(name)
                            val all = item.optJSONArray("all") ?: return@mapNotNull null
                            JSONObject()
                                .put("name", name)
                                .put("type", item.optString("type"))
                                .put("now", item.optString("now"))
                                .put(
                                    "nodes",
                                    JSONArray(
                                        (0 until minOf(all.length(), 60)).map { all.optString(it) },
                                    ),
                                )
                        }
                        .take(30)
                        .toList()
                JSONObject()
                    .put("groups", JSONArray(groups))
                    .put("mode", api(running(), "/configs").optString("mode"))
                    .put("canSelectNode", settings.agentNodes)
                    .put("canEditRules", settings.agentRules)
            }
            "select" -> {
                check(settings.agentNodes) { "用户未允许 Agent 切换节点" }
                chooseNode(args.getString("group"), args.getString("node"))
                JSONObject().put("message", "已切换节点")
            }
            "rule" -> {
                check(settings.agentRules) { "用户未允许 Agent 修改规则" }
                addRule(args.getString("type"), args.getString("domain"), args.getString("target"))
                JSONObject().put("message", "已保存本地规则")
            }
            else -> error("代理操作无效")
        }
    }

    private fun applyProfile(profile: ProxyProfile) {
        val core = active
        val current = core?.takeIf { it.process.isAlive && mutable.value.profileId == profile.id }
        val previous = profiles.read(profile.id)
        if (current != null) reload(current, profile)
        try {
            profiles.save(profile)
        } catch (error: Exception) {
            if (current != null) runCatching { reload(current, previous) }
            throw error
        }
    }

    private fun reload(core: Core, profile: ProxyProfile) {
        val config = File(home, "candidate.yaml")
        config.writeText(render(profile, core.port, core.controller, core.token))
        try {
            api(
                core,
                "/configs?force=true",
                "PUT",
                JSONObject().put("path", config.absolutePath),
                timeout = 16000,
            )
        } finally {
            config.delete()
        }
    }

    private fun render(profile: ProxyProfile, port: Int, controller: Int, token: String): String {
        val config = MihomoProfiles.config(profile)
        config["mixed-port"] = port
        config["external-controller"] = "127.0.0.1:$controller"
        config["secret"] = token
        config["bind-address"] = "127.0.0.1"
        config["allow-lan"] = false
        config["profile"] = mapOf("store-selected" to true)
        return SafeYaml.write(config)
    }

    private fun running(): Core = active?.takeIf { it.process.isAlive } ?: error("请先启动代理")

    private fun api(
        core: Core,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        timeout: Int = 4000,
    ): JSONObject {
        val connection =
            URL("http://127.0.0.1:${core.controller}$path").openConnection(Proxy.NO_PROXY)
                as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.setRequestProperty("Authorization", "Bearer ${core.token}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "Mihomo 拒绝此操作（HTTP $code），请查看内核日志" }
            val text = connection.inputStream.use { it.boundedBytes(8_000_000).decodeToString() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock { block() }
        }

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val guestProxyHost = "198.18.0.1"

        fun withProxyEnvironment(command: String, port: Int?, guest: Boolean = false): String {
            if (port == null) return command
            require(port in 1..65535)
            val host = if (guest) guestProxyHost else "127.0.0.1"
            val url = "http://$host:$port"
            return "export http_proxy=$url https_proxy=$url HTTP_PROXY=$url HTTPS_PROXY=$url all_proxy=$url ALL_PROXY=$url;\n$command"
        }

        fun stopWithAgentService() {
            instance?.let { manager -> manager.scope.launch { manager.stop() } }
        }

        @Volatile private var instance: MihomoManager? = null

        fun get(context: Context): MihomoManager =
            instance
                ?: synchronized(this) {
                    instance ?: MihomoManager(context.applicationContext).also { instance = it }
                }
    }
}
