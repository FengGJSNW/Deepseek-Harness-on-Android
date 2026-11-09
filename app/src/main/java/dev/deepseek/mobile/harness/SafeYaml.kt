package dev.deepseek.mobile.harness

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal object SafeYaml {
    private val settings =
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(32)
            .setCodePointLimit(2_000_000)
            .build()

    fun mapping(text: String): MutableMap<String, Any?> {
        val value =
            try {
                Load(settings).loadFromString(text) as? Map<*, *>
            } catch (_: RuntimeException) {
                error("YAML 格式无效、存在重复字段或超过解析限制")
            } ?: error("YAML 顶层必须是映射")
        return value.entries.associateTo(linkedMapOf()) {
            (it.key as? String ?: error("YAML 字段名必须是文字")) to it.value
        }
    }

    fun write(value: Map<String, Any?>): String =
        Dump(DumpSettings.builder().build()).dumpToString(value)
}
