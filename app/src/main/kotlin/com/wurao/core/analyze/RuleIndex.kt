package com.wurao.core.analyze

import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.selector.BoolExpr
import com.wurao.core.selector.NodeAdapter
import com.wurao.core.selector.Selector
import com.wurao.core.selector.Value

/**
 * 规则索引 —— **省电的核心**。
 *
 * 问题：订阅导进来是几千条规则。朴素做法是「每条规则各自遍历一遍整棵 View 树」，
 * 那就是 规则数 × 树节点数，页面一出现就跑两次，纯属烧电。
 *
 * 做法（照 CSS 选择器那套思路，从右往左匹配）：
 * 1. 每条规则取它**最后一段**（叶子，真正要命中的那个控件）的**精确特征**当钥匙：
 *    `[vid="x"]` → v:x，`[text="y"]` → t:y，`[desc="z"]` → d:z，类名 → c:类名；
 * 2. 遍历树**只走一遍**，对每个节点算出它的几把钥匙，只把对应钥匙下的规则拿来试。
 *
 * 于是开销从「规则数 × 节点数」降到「节点数 × 平均几条候选」。
 *
 * **保守原则**：只有**精确等值**才当钥匙。`[text*="跳过"]` 这种包含匹配不能当精确钥匙 ——
 * 否则 text 是「跳过广告」的节点就压根不会被当成候选，规则静默失效。
 */
object RuleIndex {

    /** 一条规则 + 解析好的选择器 + 它的叶子钥匙。 */
    class Entry(val rule: Rule, val selector: Selector, val key: String)

    /** 钥匙取不出来时的兜底桶：这些规则只能对每个节点都试一遍。 */
    const val KEY_ANY = "*"

    /** 某个 App 的匹配计划。 */
    class Plan(entries: List<Entry>) {
        private val byKey: Map<String, List<Entry>> =
            entries.filter { it.key != KEY_ANY }.groupBy { it.key }
        val any: List<Entry> = entries.filter { it.key == KEY_ANY }

        fun forKeys(keys: List<String>): List<Entry> {
            val out = ArrayList<Entry>(any.size + 2)
            out.addAll(any)
            for (k in keys) byKey[k]?.let { out.addAll(it) }
            return out
        }

        /** 排障用：这个 App 有多少规则、多少是"只能逐个试"的。 */
        val size: Int get() = any.size + byKey.values.sumOf { it.size }
    }

    /**
     * 按包名建索引。只收能落到具体 App 的规则（全局规则另有一份）。
     * 解析一次缓存起来 —— 解析选择器本身也不便宜。
     */
    fun build(rules: List<Rule>): Map<String, Plan> {
        val out = HashMap<String, MutableList<Entry>>()
        for (r in rules) {
            val pkg = when (val s = r.scope) {
                is Scope.App -> s.pkg
                is Scope.Component -> s.pkg
                Scope.Global -> continue
            }
            val sel = runCatching { com.wurao.core.selector.SelectorParser(r.target).parse() }
                .getOrNull() ?: continue
            val list = out.getOrPut(pkg) { ArrayList() }
            list.add(Entry(r, sel, leafKey(sel)))
        }
        return out.mapValues { (_, v) -> Plan(v) }
    }

    /** 全局（不挑 App）的规则。 */
    fun globalPlan(rules: List<Rule>): Plan {
        val entries = rules.filter { it.scope is Scope.Global }.mapNotNull { r ->
            val sel = runCatching { com.wurao.core.selector.SelectorParser(r.target).parse() }
                .getOrNull() ?: return@mapNotNull null
            Entry(r, sel, leafKey(sel))
        }
        return Plan(entries)
    }

    /** 一个节点身上可用的钥匙：资源 id / 文案 / 描述 / 类名。 */
    fun <T> keysOf(adapter: NodeAdapter<T>, node: T): List<String> {
        val out = ArrayList<String>(4)
        (adapter.attr(node, listOf("vid")) as? String)?.let { if (it.isNotBlank()) out.add("v:" + it) }
        (adapter.attr(node, listOf("text")) as? String)?.let { if (it.isNotBlank()) out.add("t:" + it) }
        (adapter.attr(node, listOf("desc")) as? String)?.let { if (it.isNotBlank()) out.add("d:" + it) }
        adapter.className(node)?.let { if (it.isNotBlank()) out.add("c:" + it) }
        return out
    }

    /** 这个节点值得试的规则（候选）。 */
    fun <T> candidates(plan: Plan, adapter: NodeAdapter<T>, node: T): List<Entry> =
        plan.forKeys(keysOf(adapter, node))

    /**
     * 叶子钥匙。**只有精确等值才算数**，模糊/取反一律退回 [KEY_ANY]。
     */
    fun leafKey(sel: Selector): String {
        val leaf = sel.units.lastOrNull() ?: return KEY_ANY
        val exact = HashMap<String, String>()
        collectEq(leaf.expr, exact)
        // 注意：id 与 vid 在两套适配器里都有，这里都认
        exact["vid"]?.let { return "v:" + it }
        exact["id"]?.let { return "v:" + it }
        exact["text"]?.let { return "t:" + it }
        exact["desc"]?.let { return "d:" + it }
        val cn = leaf.className?.trim()
        if (!cn.isNullOrBlank() && cn != "*") return "c:" + cn
        return KEY_ANY
    }

    private fun collectEq(e: BoolExpr, out: HashMap<String, String>) {
        when (e) {
            is BoolExpr.Cmp -> {
                // 只认精确等值：包含(*=)、前缀(^=)、不等(!=) 都不是
                if (isEq(e.op.sym) && e.right is Value.Str && e.left.path.size == 1) {
                    out[e.left.path[0]] = (e.right as Value.Str).v
                }
            }

            is BoolExpr.And -> e.parts.forEach { collectEq(it, out) }
            is BoolExpr.Not -> {}
            is BoolExpr.Or -> {}
            BoolExpr.Always -> {}
        }
    }

    private fun isEq(sym: String): Boolean = sym == "=" || sym == "=="
}
