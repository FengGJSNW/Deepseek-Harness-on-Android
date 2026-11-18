package dev.deepseek.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.deepseek.mobile.harness.boundedBytes
import dev.deepseek.mobile.proxy.MihomoManager
import dev.deepseek.mobile.proxy.MihomoProfiles
import dev.deepseek.mobile.proxy.ProxyProfile
import dev.deepseek.mobile.proxy.ProxyRuleRow
import dev.deepseek.mobile.proxy.noResolveRuleTypes
import dev.deepseek.mobile.proxy.proxyRuleTypes
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MihomoSettings() {
    val context = LocalContext.current
    val manager = remember { MihomoManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(emptyList<ProxyProfile>()) }
    var page by remember { mutableStateOf("概览") }
    var traffic by
        remember(state.profileId, state.running) { mutableStateOf<ProxyTrafficSample?>(null) }
    var trafficHistory by
        remember(state.profileId, state.running) { mutableStateOf(emptyList<ProxyTrafficRate>()) }
    var connectionCount by remember(state.profileId, state.running) { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf(JSONObject()) }
    var config by remember { mutableStateOf(JSONObject()) }
    var providers by remember { mutableStateOf(emptyList<Triple<String, String, String>>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var add by remember { mutableStateOf("") }
    var fileYaml by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ProxyProfile?>(null) }
    var removing by remember { mutableStateOf<ProxyProfile?>(null) }
    var group by remember { mutableStateOf("") }
    var query by remember(page, group) { mutableStateOf("") }
    var nodeSort by remember { mutableStateOf("默认") }
    var hideUnavailable by remember { mutableStateOf(false) }
    var limit by remember(page, group, query) { mutableIntStateOf(40) }
    var delays by
        remember(state.profileId, state.running) { mutableStateOf(emptyMap<String, Int>()) }
    var ruleRows by remember { mutableStateOf(emptyList<ProxyRuleRow>()) }
    var ruleTargets by remember { mutableStateOf(emptyList<String>()) }
    var ruleEdit by remember { mutableStateOf<ProxyRuleRow?>(null) }
    var ruleDelete by remember { mutableStateOf<ProxyRuleRow?>(null) }
    var measurement by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(state.profileId, state.running, page) {
        measurement?.cancel()
        measurement = null
    }
    var log by remember { mutableStateOf(emptyList<String>()) }
    fun run(block: suspend () -> Unit) {
        if (loading) return
        loading = true
        status = ""
        scope.launch {
            try {
                block()
                refresh++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message.orEmpty().ifBlank { "操作未完成" }
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(page, state.running, state.profileId, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var first = true
            var previousTraffic: ProxyTrafficSample? = null
            do {
                try {
                    if (first) profiles = manager.list()
                    if (page == "规则") {
                        ruleRows =
                            if (state.profileId.isBlank()) emptyList() else manager.ruleRows()
                        ruleTargets =
                            if (state.profileId.isBlank()) emptyList() else manager.ruleTargets()
                    }
                    if (state.running) {
                        if (first) config = manager.data("/configs")
                        payload =
                            when (page) {
                                "节点" -> manager.data("/proxies")
                                "概览",
                                "连接" -> manager.data("/connections")
                                else -> JSONObject()
                            }
                        if (page in setOf("概览", "连接")) {
                            connectionCount = payload.optJSONArray("connections")?.length()
                            val next =
                                if (payload.has("uploadTotal") && payload.has("downloadTotal"))
                                    ProxyTrafficSample(
                                        android.os.SystemClock.elapsedRealtime(),
                                        payload.optLong("uploadTotal"),
                                        payload.optLong("downloadTotal"),
                                    )
                                else null
                            val rate = next?.let { proxyTrafficRate(previousTraffic, it) }
                            if (next == null || (previousTraffic != null && rate == null))
                                trafficHistory = emptyList()
                            else if (rate != null)
                                trafficHistory = (trafficHistory + rate).takeLast(30)
                            previousTraffic = next
                            traffic = next
                        }
                        if (page == "配置" && first)
                            providers =
                                listOf("proxies", "rules").flatMap { kind ->
                                    val rows =
                                        manager.data("/providers/$kind").optJSONObject("providers")
                                            ?: JSONObject()
                                    rows
                                        .keys()
                                        .asSequence()
                                        .sorted()
                                        .map { name ->
                                            Triple(
                                                kind,
                                                name,
                                                rows
                                                    .optJSONObject(name)
                                                    ?.optString("vehicleType")
                                                    .orEmpty(),
                                            )
                                        }
                                        .toList()
                                }
                    } else {
                        config = JSONObject()
                        payload =
                            if (page == "节点" && state.profileId.isNotBlank())
                                manager.data("/proxies")
                            else JSONObject()
                        providers = emptyList()
                    }
                    if (page == "日志") log = manager.logs()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    status = error.message.orEmpty()
                }
                first = false
                if (page !in setOf("概览", "连接", "日志") || (!state.running && page != "日志")) break
                delay(1800)
            } while (true)
        }
    }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                run {
                    fileYaml =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use {
                                it.boundedBytes(MihomoProfiles.maxYamlBytes)
                                    .decodeToString(throwOnInvalidSequence = true)
                            } ?: error("无法打开配置文件")
                        }
                    add = "file"
                }
        }
    val selected = profiles.firstOrNull { it.id == state.profileId }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        DshaTextButton(
            onClick = {
                status = ""
                refresh++
            },
            enabled = !loading,
        ) {
            Text("刷新")
        }
    }
    if (page != "概览")
        SettingsStatusCard(
            AppIcons.Globe,
            if (state.running) "代理运行中" else "代理已停止",
            selected?.name ?: "导入订阅或 YAML 后可启动代理",
            tint = if (state.running) WhaleBlue else WhaleMuted,
            announce = true,
        )
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank())
        SettingsStatusCard(
            AppIcons.Description,
            status,
            tint = MaterialTheme.colorScheme.error,
            announce = true,
        )
    DshaSegments(
        listOf("概览", "配置", "节点", "规则", "连接", "日志").map { it to it },
        page,
        {
            page = it
            payload = JSONObject()
        },
    )
    if (page == "概览") {
        MihomoDashboard(
            state,
            selected?.name,
            loading,
            config.optString("mode", selected?.mode.orEmpty()),
            connectionCount,
            traffic,
            trafficHistory,
            onPower = { run { if (state.running) manager.stop() else manager.start() } },
            onMode = { mode -> run { manager.mode(mode) } },
            onProfiles = { page = "配置" },
            onConnections = { page = "连接" },
        )
        DshaCard {
            DshaCardHeader(AppIcons.Browser, "代理范围")
            ToggleSetting("应用内网页", "浏览工具与应用内网页", state.browser, enabled = !loading) {
                run { manager.toggle("browser", it) }
            }
            ToggleSetting("终端命令", "支持代理环境变量的命令", state.terminal, enabled = !loading) {
                run { manager.toggle("terminal", it) }
            }
        }
    } else if (page == "配置") {
        SettingsGroup {
            ToggleSetting(
                "自动更新订阅",
                "内核运行期间，每 24 小时更新使用中的订阅",
                state.autoUpdate,
                enabled = !loading,
            ) {
                run { manager.toggle("autoUpdate", it) }
            }
        }
        DshaCard {
            DshaCardHeader(AppIcons.Description, "添加配置")
            FlowRow {
                DshaButton(
                    style = DshaButtonStyle.Plain,
                    enabled = !loading,
                    onClick = { add = "url" },
                ) {
                    Text("导入订阅")
                }
                DshaButton(
                    style = DshaButtonStyle.Plain,
                    enabled = !loading,
                    onClick = { pick.launch(arrayOf("*/*")) },
                ) {
                    Text("导入 YAML")
                }
                DshaButton(
                    style = DshaButtonStyle.Plain,
                    enabled = !loading,
                    onClick = { add = "text" },
                ) {
                    Text("粘贴 YAML")
                }
            }
        }
        for (profile in profiles) {
            DshaCard(padding = PaddingValues(8.dp)) {
                SettingsLink(
                    AppIcons.Description,
                    profile.name,
                    (if (profile.id == state.profileId) "使用中 · " else "") +
                        if (profile.source.isBlank()) "本地配置" else "订阅",
                ) {
                    run { manager.activate(profile.id) }
                }
                FlowRow {
                    if (profile.source.isNotBlank())
                        DshaButton(
                            style = DshaButtonStyle.Plain,
                            enabled = !loading,
                            onClick = { run { manager.refresh(profile.id) } },
                        ) {
                            Text("更新订阅")
                        }
                    DshaButton(
                        style = DshaButtonStyle.Plain,
                        enabled = !loading,
                        onClick = { editing = profile },
                    ) {
                        Text("编辑 YAML")
                    }
                    DshaButton(
                        style = DshaButtonStyle.Plain,
                        enabled = !loading,
                        onClick = { removing = profile },
                    ) {
                        Text("删除")
                    }
                }
            }
        }
        if (providers.isNotEmpty()) {
            HorizontalDivider(color = WhaleLine)
            Text("代理与规则提供者", fontSize = 15.sp)
            providers.forEach { (kind, name, type) ->
                SettingsLink(
                    AppIcons.Description,
                    name,
                    "${if (kind == "proxies") "代理" else "规则"} · $type · 更新",
                ) {
                    run { manager.updateProvider(kind, name) }
                }
            }
        }
    } else if (page == "日志") {
        if (log.isEmpty()) SettingsStatusCard(AppIcons.Terminal, "暂无内核日志")
        log.takeLast(80).asReversed().forEach {
            Text(it, fontSize = 11.sp, fontFamily = DshaFonts.mono)
        }
    } else if (page == "节点") {
        DshaSegments(
            listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
            config.optString("mode").lowercase(),
            { mode -> run { manager.mode(mode) } },
            enabled = state.running && !loading,
        )
        if (!state.running) Text("显示上一次缓存的节点，选择将在下次启动时应用。", fontSize = 12.sp, color = WhaleMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshaButton(
                style = DshaButtonStyle.Plain,
                enabled = state.running && !loading,
                onClick = {
                    if (measurement != null) measurement?.cancel()
                    else
                        measurement = scope.launch {
                            try {
                                status = ""
                                delays = emptyMap()
                                val measuredProfile = state.profileId
                                manager.measureAll { batch ->
                                    withContext(Dispatchers.Main) {
                                        if (
                                            manager.state.value.profileId == measuredProfile &&
                                                manager.state.value.running
                                        )
                                            delays = delays + batch
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                status = error.message.orEmpty()
                            } finally {
                                if (measurement == coroutineContext[Job]) measurement = null
                            }
                        }
                },
            ) {
                Text(if (measurement == null) "全局测速" else "停止测速")
            }
            if (measurement != null)
                Text("已测 ${delays.size} 个", color = WhaleMuted, fontSize = 12.sp)
        }
        val proxies = payload.optJSONObject("proxies") ?: JSONObject()
        val groups =
            proxies
                .keys()
                .asSequence()
                .filter { proxies.optJSONObject(it)?.has("all") == true }
                .sorted()
                .toList()
        val name = group.takeIf { it in groups } ?: groups.firstOrNull().orEmpty()
        val row = proxies.optJSONObject(name)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { key ->
                DshaButton(
                    onClick = { group = key },
                    style = if (name == key) DshaButtonStyle.Filled else DshaButtonStyle.Tonal,
                ) {
                    Text(key)
                }
            }
        }
        if (row != null) {
            Text(
                "$name · ${row.optString("type")} · ${row.optString("now")}",
                fontSize = 13.sp,
                color = WhaleMuted,
            )
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("筛选节点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val all = row.optJSONArray("all") ?: JSONArray()
            val nodes =
                sortedProxyNodes(
                    List(all.length()) { all.optString(it) }.filter { it.contains(query, true) },
                    delays,
                    nodeSort,
                    hideUnavailable,
                )
            DshaSegments(listOf("默认", "延迟", "名称").map { it to it }, nodeSort, { nodeSort = it })
            ToggleSetting("隐藏不可用节点", "未测速的节点仍然显示", hideUnavailable) { hideUnavailable = it }
            val selector = row.optString("type").equals("Selector", true)
            nodes.take(limit).chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { node ->
                        val chosen = node == row.optString("now")
                        MihomoNodeCard(
                            node,
                            proxies.optJSONObject(node)?.optString("type").orEmpty(),
                            chosen,
                            delays[node],
                            enabled = !loading && selector,
                            canMeasure = state.running && !loading && measurement == null,
                            modifier = Modifier.weight(1f),
                            onSelect = { run { manager.choose(name, node) } },
                            onMeasure = {
                                run {
                                    val measuredProfile = state.profileId
                                    val result = manager.delay(node)
                                    if (
                                        manager.state.value.profileId == measuredProfile &&
                                            manager.state.value.running
                                    )
                                        delays = delays + (node to result)
                                }
                            },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (nodes.size > limit)
                DshaButton(style = DshaButtonStyle.Plain, onClick = { limit += 40 }) {
                    Text("显示更多")
                }
        }
    } else if (page == "规则") {
        DshaButton(
            style = DshaButtonStyle.Plain,
            enabled = !loading && selected != null,
            onClick = {
                ruleEdit = null
                add = "rule"
            },
        ) {
            Text("新增规则")
        }
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("筛选规则") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val filtered = ruleRows.withIndex().filter { it.value.raw.contains(query, true) }
        filtered.take(limit).forEach { (index, rule) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", Modifier.width(30.dp), fontSize = 12.sp, color = WhaleMuted)
                Column(Modifier.weight(1f)) {
                    Text(rule.value.ifBlank { rule.type }, maxLines = 2, fontSize = 14.sp)
                    Text(
                        "${rule.type} · ${rule.target}",
                        maxLines = 2,
                        color = WhaleMuted,
                        fontSize = 12.sp,
                    )
                }
                DshaButton(
                    style = DshaButtonStyle.Plain,
                    enabled = !loading,
                    onClick = {
                        ruleEdit = rule
                        add = "rule"
                    },
                ) {
                    Text("编辑")
                }
                DshaButton(
                    style = DshaButtonStyle.Plain,
                    enabled = !loading,
                    onClick = { ruleDelete = rule },
                ) {
                    Text("删除")
                }
            }
            HorizontalDivider(color = WhaleLine)
        }
        if (filtered.size > limit)
            DshaButton(style = DshaButtonStyle.Plain, onClick = { limit += 40 }) { Text("显示更多") }
    } else if (!state.running) {
        SettingsStatusCard(AppIcons.Globe, "启动代理后查看$page")
    } else if (page == "连接") {
        val connections = payload.optJSONArray("connections") ?: JSONArray()
        Text(
            "${connections.length()} 个连接 · ↑ ${proxyBytes(payload.optLong("uploadTotal"))} · ↓ ${proxyBytes(payload.optLong("downloadTotal"))}",
            fontSize = 13.sp,
        )
        DshaButton(
            style = DshaButtonStyle.Plain,
            enabled = !loading,
            onClick = { run { manager.disconnect() } },
        ) {
            Text("断开全部连接")
        }
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("筛选连接") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val rows =
            List(connections.length()) { connections.optJSONObject(it) }
                .filterNotNull()
                .filter {
                    it.optJSONObject("metadata")?.toString().orEmpty().contains(query, true)
                }
        rows.take(limit).forEach { item ->
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            val host = metadata.optString("host").ifBlank { metadata.optString("destinationIP") }
            DshaCard {
                DshaCardHeader(AppIcons.Browser, "$host:${metadata.optString("destinationPort")}")
                Text(
                    item.optJSONArray("chains")?.join(" → ").orEmpty(),
                    fontSize = 12.sp,
                    color = DshaSurface.muted,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "↓ ${proxyBytes(item.optLong("download"))} · ↑ ${proxyBytes(item.optLong("upload"))}",
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                        color = DshaSurface.muted,
                    )
                    DshaButton(
                        onClick = { run { manager.disconnect(item.getString("id")) } },
                        enabled = !loading,
                        style = DshaButtonStyle.Danger,
                    ) {
                        Text("断开")
                    }
                }
            }
        }
        if (rows.size > limit)
            DshaButton(style = DshaButtonStyle.Plain, onClick = { limit += 40 }) { Text("显示更多") }
    }
    val type = add
    if (type in setOf("url", "file", "text")) {
        var name by remember(type) { mutableStateOf(if (type == "url") "订阅" else "本地配置") }
        var content by remember(type) { mutableStateOf(if (type == "file") fileYaml else "") }
        WhaleSheet(
            if (type == "url") "导入订阅" else "导入 Mihomo 配置",
            "支持 Clash / Mihomo YAML",
            {
                add = ""
                fileYaml = ""
            },
        ) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("配置名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (type != "file")
                OutlinedTextField(
                    content,
                    { content = it },
                    label = { Text(if (type == "url") "HTTPS 订阅地址" else "配置 YAML") },
                    visualTransformation =
                        if (type == "url") PasswordVisualTransformation()
                        else UprightSymbols.transformation,
                    singleLine = type == "url",
                    maxLines = if (type == "url") 1 else 9,
                    modifier = Modifier.fillMaxWidth(),
                )
            DshaButton(
                enabled = !loading && name.isNotBlank() && content.isNotBlank(),
                onClick = {
                    run {
                        manager.import(
                            name,
                            if (type == "url") "" else content,
                            if (type == "url") content.trim() else "",
                        )
                        add = ""
                        fileYaml = ""
                    }
                },
            ) {
                Text("导入")
            }
            if (status.isNotBlank())
                Text(status, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
    editing?.let { profile ->
        var yaml by remember(profile.id) { mutableStateOf(profile.yaml) }
        WhaleSheet("编辑 ${profile.name}", "保存后重载正在使用的配置", { editing = null }) {
            OutlinedTextField(
                yaml,
                { yaml = it },
                label = { Text("配置 YAML") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 11,
                visualTransformation = UprightSymbols.transformation,
            )
            DshaButton(
                enabled = !loading,
                onClick = {
                    run {
                        manager.edit(profile.id, yaml)
                        editing = null
                    }
                },
            ) {
                Text("保存")
            }
            if (status.isNotBlank())
                Text(status, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
    removing?.let { profile ->
        WhaleSheet("删除 ${profile.name}？", "删除配置及其本地规则", { removing = null }) {
            DshaButton(
                enabled = !loading,
                onClick = {
                    run {
                        manager.remove(profile.id)
                        removing = null
                    }
                },
            ) {
                Text("删除配置")
            }
        }
    }
    ruleDelete?.let { row ->
        WhaleSheet("删除规则？", row.raw, { ruleDelete = null }) {
            DshaButton(
                enabled = !loading,
                onClick = {
                    run {
                        manager.deleteRule(row)
                        ruleDelete = null
                    }
                },
            ) {
                Text("删除规则")
            }
            if (status.isNotBlank())
                Text(status, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
    if (type == "rule") {
        val original = ruleEdit
        var ruleType by
            remember(original) {
                mutableStateOf(original?.type?.takeIf { it in proxyRuleTypes } ?: "DOMAIN-SUFFIX")
            }
        var value by remember(original) { mutableStateOf(original?.value.orEmpty()) }
        var target by remember(original) { mutableStateOf(original?.target ?: "DIRECT") }
        var noResolve by remember(original) { mutableStateOf(original?.noResolve ?: false) }
        WhaleSheet(if (original == null) "新增规则" else "编辑规则", "规则保存在当前配置，订阅更新后仍保留。", { add = "" }) {
            ProxyRulePicker(
                "规则类型",
                "${proxyRuleTypes[ruleType]} ($ruleType)",
                proxyRuleTypes.map { it.key to "${it.value} (${it.key})" },
            ) {
                ruleType = it
            }
            if (ruleType != "MATCH")
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text("规则内容") },
                    singleLine = true,
                    placeholder = {
                        Text(if (ruleType.startsWith("IP-CIDR")) "192.0.2.0/24" else "example.com")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            ProxyRulePicker("代理策略", target, ruleTargets.map { it to it }) { target = it }
            if (ruleType in noResolveRuleTypes)
                ToggleSetting("不解析域名", "仅匹配已有的 IP 地址", noResolve) { noResolve = it }
            DshaButton(
                enabled = !loading && (value.isNotBlank() || ruleType == "MATCH"),
                onClick = {
                    run {
                        manager.editRule(original, ruleType, value.trim(), target, noResolve)
                        add = ""
                    }
                },
            ) {
                Text("保存规则")
            }
            if (status.isNotBlank())
                Text(status, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProxyRulePicker(
    label: String,
    chosen: String,
    choices: List<Pair<String, String>>,
    select: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LiquidMenuAnchor(
        expanded,
        { expanded = false },
        toggleAnchor = true,
        anchor = {
            DshaButton(style = DshaButtonStyle.Plain, onClick = { expanded = !expanded }) {
                Text("$label · $chosen ▾")
            }
        },
    ) {
        choices.forEach { (key, label) ->
            GlassMenuRow(AppIcons.Check, label, selected = chosen == key) {
                select(key)
                expanded = false
            }
        }
    }
}

private fun proxyBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
        bytes >= 1024 -> "${bytes / 1024} KiB"
        else -> "$bytes B"
    }

@Composable
internal fun MihomoAgentSettings() {
    val context = LocalContext.current
    val manager = remember(context) { MihomoManager.get(context) }
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun toggle(key: String, value: Boolean) {
        scope.launch {
            runCatching { manager.toggle(key, value) }
                .onFailure { AssistantState.error = it.message.orEmpty() }
        }
    }
    ToggleSetting("允许 Agent 启动代理", "允许 Agent 启动当前配置", state.agentStart) { toggle("agentStart", it) }
    ToggleSetting("允许 Agent 切换节点", "允许选择当前配置已有节点", state.agentNodes) { toggle("agentNodes", it) }
    ToggleSetting("允许 Agent 修改规则", "允许添加或替换域名规则", state.agentRules) { toggle("agentRules", it) }
}
