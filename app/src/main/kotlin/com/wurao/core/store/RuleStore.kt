package com.wurao.core.store

import com.wurao.core.model.Exclusion
import com.wurao.core.model.Rule
import com.wurao.core.source.GkdSubscription

/**
 * 规则库（模块进程侧）。
 *
 * 三条规矩：
 * 1. **导入幂等**：同一份订阅反复导入，先清掉这个来源的旧规则再写新的 —— 点十次还是那一份；
 * 2. **订阅更新不覆盖用户自抓的**：只清同来源，[com.wurao.core.model.Origin.User] 那条不受影响
 *    （自定规则是用户亲眼看到才建的，最可信）；
 * 3. **关掉不等于删除**：停用只改 enabled，规则还在（删了用户就找不回来）。
 */
object RuleStore {

    private val rules = LinkedHashMap<String, Rule>()
    private val exclusions = HashMap<String, Exclusion>()

    /** 持久化路径：/sdcard/Android/data/com.wurao.app/files/rules.json（hook 进程能读到）。 */
    private val persistPath = "/sdcard/Android/data/com.wurao.app/files/rules.json"

    /** 保存到磁盘（hook 进程读）。 */
    private fun persist() {
        runCatching {
            val json = rules.values.joinToString("\n") { r ->
                // 一行一条：id|domain|target|act|enabled|scope.pkg
                val pkg = (r.scope as? com.wurao.core.model.Scope.App)?.pkg ?: ""
                "${r.id}|${r.domain.key}|${r.target}|${r.act.key}|${r.enabled}|$pkg"
            }
            java.io.File(persistPath).apply { parentFile?.mkdirs() }.writeText(json)
        }
    }

    data class ImportReport(
        val added: Int,
        val replaced: Int,
        val skipped: Int,
        val totalInSource: Int,
    ) {
        override fun toString(): String =
            "新增 $added · 覆盖 $replaced · 跳过 $skipped（源内共 $totalInSource 条）"
    }

    /** 导入一份订阅。返回报告（供界面显示，不允许静默）。 */
    fun import(result: GkdSubscription.Result): ImportReport {
        val subsIds = result.rules.map { it.origin }.toSet() +
            result.skipped.mapNotNull { null }
        var added = 0
        var replaced = 0

        // ① 先清掉这几个来源的旧规则（幂等的关键）
        val originKeys = result.rules.map { it.origin.idPrefix }.toSet()
        rules.entries.removeIf { it.value.origin.idPrefix in originKeys }

        // ② 写入新的
        for (r in result.rules) {
            val existed = false // 上一步已清完，这里全是新增；保留字段便于以后做增量
            rules[r.id] = r
            if (existed) replaced++ else added++
        }

        // ③ 例外名单直接并入（全局规则在这些应用上不生效）
        exclusions.putAll(result.exclusions)

        return ImportReport(
            added = added,
            replaced = replaced,
            skipped = result.skipped.size,
            totalInSource = result.total,
        ).also {
            require(result.ok) { "订阅解析总数不守恒：${result.rules.size} + ${result.skipped.size} != ${result.total}" }
        }
    }

    /** 用户自己抓的 / 手工改的规则：增删改都走这里。 */
    fun upsert(rule: Rule) {
        rules[rule.id] = rule
        persist()
    }

    fun remove(id: String): Rule? = rules.remove(id).also { persist() }

    fun get(id: String): Rule? = rules[id]

    /** 停用/启用（不删除）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        rules[id]?.let { rules[id] = it.copy(enabled = enabled) }
        persist()
    }

    fun setExcluded(pkg: String, excluded: Boolean) {
        if (excluded) exclusions[pkg] = Exclusion(pkg, enable = false)
        else exclusions.remove(pkg)
    }

    /** 某个应用当前生效的规则：按「自抓 > 订阅 > 内置」排，页面闸由调用方再判。 */
    fun forApp(pkg: String): List<Rule> = rules.values
        .filter { it.enabled && it.enabledFor(pkg, exclusions) }
        .sortedBy { priorityOf(it) }

    private fun priorityOf(r: Rule): Int = when (r.origin) {
        com.wurao.core.model.Origin.User -> 0
        is com.wurao.core.model.Origin.Subscription -> 1
        com.wurao.core.model.Origin.Builtin -> 2
        is com.wurao.core.model.Origin.Seed -> 1
    }

    fun all(): List<Rule> = rules.values.toList()

    fun exclusions(): Map<String, Exclusion> = exclusions.toMap()

    fun clear() {
        rules.clear()
        exclusions.clear()
    }
}
