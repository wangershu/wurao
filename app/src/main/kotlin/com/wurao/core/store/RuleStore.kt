package com.wurao.core.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


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

    /**
     * 规则落位（一次性写，不是运行时同步）：
     * 写进模块自己的 SharedPreferences，每个应用一个文件 wurao_rules_<pkg>；
     * hook 进程用 libxposed 的 getRemotePreferences 读自己那一个（框架托管，
     * 不走 IPC、不需要无扰在后台、不受 MIUI 自启动管控拦）。
     * - 控件规则：按应用切分，只落位已勾选 / 抓过规则的应用
     * - 域名规则：全存一份 wurao_domains（全局拦截，不切分）
     * 其余订阅规则留在内存，等该应用被勾选再 land(pkg)。
     */
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private fun pkgOf(r: Rule): String = when (val sc = r.scope) {
        is com.wurao.core.model.Scope.App -> sc.pkg
        is com.wurao.core.model.Scope.Component -> sc.pkg
        com.wurao.core.model.Scope.Global -> ""
    }

    /**
     * 变更后重新落位（合并不重建）。
     * 导入订阅是逐条 upsert（几万条），每次都 cancel+launch 一个协程也会把启动拖慢；
     * 这里只置脏标记，已有待执行任务就直接复用，落位后若期间又有改动再跑一轮，
     * 保证最终态一定落位。
     */
    @Volatile private var batching = false
    @Volatile private var dirty = false
    @Volatile private var pendingJob: Job? = null

    /** 批量导入/启动恢复期间关掉落位，结束统一一次。 */
    fun beginBatch() { batching = true }
    fun endBatch() { batching = false; persist() }

    private fun persist() {
        if (batching) return
        dirty = true
        synchronized(this) {
            if (pendingJob?.isActive == true) return
            pendingJob = ioScope.launch {
                while (dirty) {
                    dirty = false
                    delay(300)
                    if (!batching) doLand()
                }
            }
        }
    }

    private fun doLand() {
        runCatching {
            val scope = com.wurao.core.XposedBridge.scope()
            val hides = rules.values.filter { r ->
                r.enabled && r.act == com.wurao.core.model.Act.HIDE && pkgOf(r).isNotBlank()
            }
            // 对每个勾选应用都落位（没规则的写空，用来清掉旧规则）；
            // 拿不到 scope（未绑定框架）时按有规则的应用全落
            val pkgs = if (scope.isEmpty()) hides.map { pkgOf(it) }.distinct() else scope.toList()
            val byPkg = pkgs.filter { it.isNotBlank() }.associateWith { pkg ->
                hides.filter { pkgOf(it) == pkg }.map { it.target }
            }
            // 域名规则：全局一份，不按应用切（反正都要拦）
            val domains = rules.values.filter { r ->
                r.enabled && r.domain == com.wurao.core.model.Domain.HOST
            }.map { it.target }.toMutableList()
            // 订阅域名表独立存于内部文件，也一起落位
            runCatching {
                val f = java.io.File("/data/data/com.wurao.app/files/adclose.txt")
                if (f.exists()) domains += f.readLines()
                    .filter { it.isNotBlank() && !it.trim().startsWith("#") && !it.trim().startsWith("!") }
                    .map { com.wurao.core.DomainExclusions.normalize(it) }
                    .filter { it.isNotBlank() }
            }
            // 用户单独移除的域名（误屏蔽后恢复用），落位前减掉
            val excluded = com.wurao.core.DomainExclusions.load()
            val liveDomains = domains.distinct().filter { it !in excluded }

            // 自动点按：订阅的 CLICK 规则 + 内置两条开屏跳过，一起落位到目标 App。
            // 全局规则（开屏跳过）对每个作用域应用都写一份。
            // 整体去重：内置全局开屏组与 Builtin 的「跳过」规则完全相同，
            // 不去重会连点两次、命中记录也会出现两行
            val clickRules = (
                rules.values.filter { r ->
                    r.enabled && (r.act == com.wurao.core.model.Act.CLICK ||
                        r.act == com.wurao.core.model.Act.LONG_CLICK)
                } + com.wurao.core.Builtin.rules()
            ).distinctBy { it.act.key + "|" + it.target + "|" + pkgOf(it) }
            val clickByPkg = pkgs.filter { it.isNotBlank() }.associateWith { pkg ->
                clickRules.filter { pkgOf(it).isBlank() || pkgOf(it) == pkg }
                    .map { it.act.key + "\t" + it.target }
            }

            android.util.Log.i(
                "Wurao",
                "doLand: ${byPkg.values.sumOf { it.size }} 条 HIDE / " +
                    "${clickByPkg.values.sumOf { it.size }} 条 CLICK / ${byPkg.size} 个应用 / ${liveDomains.size} 域名",
            )
            com.wurao.core.XposedBridge.land(byPkg, liveDomains, clickByPkg)
        }
    }

    /** 框架绑定后补一次落位（避免启动时 service 还没连上）。 */
    fun reland() = persist()

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

    /** 改备注（一句人话：这条规则是干嘛的）。 */
    fun setNote(id: String, note: String) {
        rules[id]?.let { rules[id] = it.copy(note = note) }
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
