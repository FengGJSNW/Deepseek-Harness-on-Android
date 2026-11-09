package dev.deepseek.mobile.proxy

import android.net.InetAddresses
import dev.deepseek.mobile.harness.SafeYaml

internal val proxyRuleTypes =
    linkedMapOf(
        "DOMAIN" to "完整域名",
        "DOMAIN-SUFFIX" to "域名后缀",
        "DOMAIN-KEYWORD" to "域名关键词",
        "IP-CIDR" to "IPv4 网段",
        "IP-CIDR6" to "IPv6 网段",
        "GEOIP" to "IP 地区",
        "GEOSITE" to "站点分类",
        "RULE-SET" to "规则集",
        "PROCESS-NAME" to "进程名",
        "MATCH" to "匹配剩余流量",
    )

internal val noResolveRuleTypes = setOf("IP-CIDR", "IP-CIDR6", "GEOIP")

internal data class ProxyRuleRow(val index: Int, val raw: String, val original: String? = null) {
    val type
        get() = raw.substringBefore(',')

    val noResolve
        get() = raw.endsWith(",no-resolve")

    val target
        get() = raw.removeSuffix(",no-resolve").substringAfterLast(',')

    val value
        get() =
            if (type == "MATCH") ""
            else raw.removeSuffix(",no-resolve").substringAfter(',').substringBeforeLast(',')
}

internal fun proxyRuleRows(profile: ProxyProfile): List<ProxyRuleRow> {
    val base =
        (SafeYaml.mapping(profile.yaml)["rules"] as? List<*>).orEmpty().filterIsInstance<String>()
    return proxyRuleRows(profile, base)
}

internal fun proxyRuleRows(profile: ProxyProfile, base: List<String>): List<ProxyRuleRow> {
    val local = profile.rules.mapIndexed { index, rule -> ProxyRuleRow(index, rule) }
    val fallback = local.lastOrNull { it.type == "MATCH" }
    val inherited =
        base
            .ifEmpty { listOf("MATCH,DIRECT") }
            .mapIndexedNotNull { index, original ->
                if (original in profile.removedRules) null
                else ProxyRuleRow(index, profile.ruleOverrides[original] ?: original, original)
            }
    return local.filter { it.type != "MATCH" } +
        inherited.filterNot { fallback != null && it.type == "MATCH" } +
        listOfNotNull(fallback)
}

internal fun formatProxyRule(
    type: String,
    value: String,
    target: String,
    noResolve: Boolean,
): String {
    require(type in proxyRuleTypes) { "请选择规则类型" }
    require(target.length in 1..120 && target.none { it == ',' || it.isISOControl() }) { "代理策略无效" }
    if (type == "MATCH") return "MATCH,$target"
    val payload = value.trim()
    require(payload.length in 1..253 && payload.none { it == ',' || it.isISOControl() }) {
        "请输入有效的规则内容"
    }
    if (type.startsWith("DOMAIN"))
        require(payload.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*"))) { "域名或关键词无效" }
    if (type in setOf("IP-CIDR", "IP-CIDR6")) {
        val address = payload.substringBefore('/')
        val prefix = payload.substringAfter('/', "").toIntOrNull()
        val bytes = runCatching {
            InetAddresses.parseNumericAddress(address).address.size
        }
            .getOrNull()
        val expected = if (type == "IP-CIDR") 4 else 16
        require(bytes == expected && prefix != null && prefix in 0..expected * 8) {
            "请输入有效的 CIDR 网段"
        }
    }
    val normalized = if (type.startsWith("DOMAIN")) payload.lowercase() else payload
    return "$type,$normalized,$target" +
        if (noResolve && type in noResolveRuleTypes) ",no-resolve" else ""
}
