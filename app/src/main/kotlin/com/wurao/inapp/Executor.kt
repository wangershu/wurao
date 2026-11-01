package com.wurao.inapp

import android.view.View
import com.wurao.core.Builtin
import com.wurao.core.HitLog
import com.wurao.core.Pacing
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.selector.Selector
import com.wurao.core.selector.SelectorMatcher
import com.wurao.core.selector.SelectorParser

/**
 * 进程内执行器：三道闸 + 匹配 + 动作。
 *
 * 三道闸（越便宜越靠前，这是耗电约束的第一道防线）：
 * 1. **进程闸** —— 这个 App 没有要跑的规则，一次匹配都不做；
 * 2. **App 闸** —— 规则对这个包是否启用（例外名单关掉的就不跑）；
 * 3. **节奏闸** —— 冷却 / 次数上限 / 匹配窗口（见 [Pacing]）。
 *
 * 返回命中的规则备注，供日志与排障用；没命中返回 null。
 */
object Executor {

    private val pacing = Pacing()

    /** 选择器解析结果缓存：编译一次反复用，别每帧都解析一遍字符串。 */
    private val selectorCache = HashMap<String, Selector>()

    /** 例外名单：全局规则在这个包上被关掉（S6 由界面写入）。 */
    private val excluded = HashSet<String>()

    /** 被停用的规则 id —— 排错时从命中记录点"停用"写进来。停用不等于删除。 */
    private val stopped = HashSet<String>()

    /** 界面送过来的规则（自抓 + 订阅 + 自动分析候选），界面写、这里读。 */
    private val remoteRules = ArrayList<Rule>()

    /**
     * 规则索引（按包名 + 叶子特征）。**在 setRules 时建好**：
     * 每次 tick 重建等于把几千条规则的选择器重解析一遍，那就白优化了。
     */
    private var plans: Map<String, com.wurao.core.analyze.RuleIndex.Plan> = emptyMap()
    private var globalPlan = com.wurao.core.analyze.RuleIndex.Plan(emptyList())

    /**
     * 排序：自己抓的 > 订阅/自动分析 > 内置。
     * 你是亲眼看到那个东西才抓的，比订阅来的更可信，所以先试它。
     */
    fun setRules(list: List<Rule>) {
        val sorted = list.sortedBy { priorityOf(it) }
        remoteRules.clear()
        remoteRules.addAll(sorted)
        plans = com.wurao.core.analyze.RuleIndex.build(sorted)
        globalPlan = com.wurao.core.analyze.RuleIndex.globalPlan(sorted)
    }

    fun setStopped(ids: Collection<String>) {
        stopped.clear()
        stopped.addAll(ids)
    }

    fun setExcluded(pkgs: Collection<String>) {
        excluded.clear()
        excluded.addAll(pkgs)
    }

    fun setPaused(v: Boolean) = pacing.setPaused(v)

    fun exclude(pkg: String) {
        excluded.add(pkg)
    }

    fun include(pkg: String) {
        excluded.remove(pkg)
    }

    fun onPageChanged(pageKey: String) = pacing.onPageChanged(pageKey, System.currentTimeMillis())

    /**
     * 跑一次。必须在主线程调用。
     *
     * @param root 当前页面的根 View
     * @param pkg 当前包名
     * @return 命中的规则备注（null = 没命中或没资格跑）
     */
    fun tick(root: View, pkg: String): String? {
        if (pacing.isPaused) return null

        // ① 进程闸 + ② App 闸：先把没资格的规则筛掉，再谈匹配
        val rules = activeRules(pkg)
        Log.d("tick: pkg=$pkg 规则=${rules.size} 页=${pacing.currentPage}")
        if (rules.isEmpty()) return null

        val adapter = ViewAdapter(pkg)
        val now = System.currentTimeMillis()

        val nodeRules = rules.filter {
            it.domain == com.wurao.core.model.Domain.NODE && pacing.allow(it, now)
        }
        if (nodeRules.isEmpty()) return null

        // 索引筛一遍：一条规则若命中，它的叶子必然是树上某个节点，
        // 所以「按叶子特征挑出来的候选」不会漏 —— 但能把绝大多数无关规则挡掉，
        // 省下的就是「每条规则各自遍历整棵树」的那份开销（几千条时非常可观）。
        val toTry = HashSet<String>()
        val pkgPlan = plans[pkg]
        walk(root, adapter) { node ->
            val keys = com.wurao.core.analyze.RuleIndex.keysOf(adapter, node)
            if (keys.isEmpty()) return@walk
            pkgPlan?.forKeys(keys)?.forEach { toTry.add(it.rule.id) }
            globalPlan.forKeys(keys).forEach { toTry.add(it.rule.id) }
        }

        for (rule in nodeRules) {
            if (rule.id !in toTry) continue

            val selector = selectorCache.getOrPut(rule.target) {
                runCatching { SelectorParser(rule.target).parse() }.getOrNull() ?: return null
            }
            val target = runCatching { SelectorMatcher(adapter).match(selector, root) }.getOrNull()
                ?: continue

            val done = runCatching { Actor.perform(rule.act, target) }.getOrDefault(false)
            if (done) {
                pacing.record(rule, now)
                val note = rule.note.ifBlank { rule.act.key }
                // 这条日志由**目标进程**打出 —— 进程内生效的证据就是它（PID 不是模块进程）
                Log.i("命中[$pkg] $note <- ${rule.target}")
                HitLog.record(rule, pkg, pacing.currentPage, now)
                Report.hit(rule, pkg, pacing.currentPage)
                return note
            }
        }
        return null
    }

    /** 遍历整棵树（显式栈，避免深树递归）。 */
    private fun <T> walk(root: T, adapter: com.wurao.core.selector.NodeAdapter<T>, visit: (T) -> Unit) {
        val stack = ArrayList<T>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            runCatching { visit(node) }
            val n = runCatching { adapter.childCount(node) }.getOrDefault(0)
            for (i in n - 1 downTo 0) {
                runCatching { adapter.child(node, i) }?.getOrNull()?.let(stack::add)
            }
        }
    }

    /**
     * 当前该跑哪些规则。
     * S2 阶段只有内置规则；S3 接上订阅后，这里换成「内置 + 订阅 + 用户自抓」的合并结果，
     * 顺序按「用户自抓 > 订阅 > 内置」（自定规则是亲眼看到才建的，最可信）。
     */
    private fun activeRules(pkg: String): List<Rule> {
        fun usable(r: Rule) = r.enabled && r.enabledFor(pkg) && !excluded.contains(pkg) && r.id !in stopped
        // 用户自己抓的规则排前面：他是亲眼看到那个广告才建的，比内置规则更可信
        return remoteRules.filter(::usable) + Builtin.rules().filter(::usable)
    }

    private fun priorityOf(r: Rule): Int = when (r.origin) {
        Origin.User -> 0
        is Origin.Subscription -> 1
        is Origin.Seed -> 1
        Origin.Builtin -> 2
    }

    /** 排障用：当前节奏状态。 */
    fun debugState(): String = "page=${pacing.currentPage} paused=${pacing.isPaused}"
}
