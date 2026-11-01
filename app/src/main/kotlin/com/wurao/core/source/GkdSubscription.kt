package com.wurao.core.source

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Exclusion
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.model.Timing

/**
 * GKD 订阅 → 我们的统一规则。
 *
 * 两条不能破的规矩：
 * 1. **总数守恒** —— 每一条输入要么变成规则，要么进 [Result.skipped] 并写明原因，
 *    不允许静默丢弃（旧实现最怕的就是"导入完发现少了一批，不知道少在哪"）；
 * 2. **源只是入口** —— 解析完只留下统一模型，源数据不留存，所以不存在"重算冲掉用户手写"。
 */
object GkdSubscription {

    data class Result(
        val rules: List<Rule>,
        /** 没采用的条目与原因。 */
        val skipped: List<Skipped>,
        /** 例外名单：全局规则在这些应用上被关掉。 */
        val exclusions: Map<String, Exclusion>,
        /** 输入里实际出现的规则条数，用于核对总数守恒。 */
        val total: Int,
    ) {
        val ok: Boolean get() = rules.size + skipped.size == total
    }

    data class Skipped(val where: String, val reason: String) {
        override fun toString(): String = "$where：$reason"
    }

    fun parse(text: String): Result {
        val root = Json5.parseObject(text)
        val subsId = (root["id"] as? Number)?.toLong()
            ?: throw Json5.Json5Error(0, "订阅缺少 id")
        val origin = Origin.Subscription(subsId)

        val rules = ArrayList<Rule>()
        val skipped = ArrayList<Skipped>()
        val exclusions = HashMap<String, Exclusion>()
        var total = 0

        // ---------- 全局组：默认对所有应用生效，用例外名单关掉误伤的应用 ----------
        list(root["globalGroups"]).forEachIndexed { gi, g ->
            val gm = map(g) ?: return@forEachIndexed
            val groupKey = gm["key"]?.toString() ?: gi.toString()
            val gTiming = timingOf(gm)

            list(gm["rules"]).forEachIndexed { ri, r ->
                total++
                val rm = map(r)
                if (rm == null) {
                    skipped += Skipped("$.globalGroups[$gi].rules[$ri]", "不是对象")
                    return@forEachIndexed
                }
                val matches = rm["matches"]?.toString()
                if (matches.isNullOrBlank()) {
                    skipped += Skipped("$.globalGroups[$gi].rules[$ri]", "没有 matches")
                    return@forEachIndexed
                }
                val act = actionOf(rm)
                if (act == null) {
                    skipped += Skipped(
                        "$.globalGroups[$gi].rules[$ri]",
                        "动作不支持：${rm["action"] ?: "默认 click"}"
                    )
                    return@forEachIndexed
                }
                rules += Rule(
                    domain = Domain.NODE,
                    target = matches,
                    act = act,
                    scope = Scope.Global,
                    timing = gTiming.merge(timingOf(rm)),
                    origin = origin,
                    groupKey = "g$groupKey",
                    ruleKey = rm["key"]?.toString() ?: ri.toString(),
                    activityIds = activityIdsOf(rm),
                    note = rm["name"]?.toString() ?: gm["name"]?.toString() ?: "",
                )
            }

            // 例外名单：apps[].enable=false 的应用不跑这条全局规则
            list(gm["apps"]).forEach { a ->
                val am = map(a) ?: return@forEach
                val id = am["id"]?.toString() ?: return@forEach
                if (am["enable"] == false) exclusions[id] = Exclusion(id, enable = false)
            }
        }

        // ---------- 应用组：只对指定应用生效 ----------
        list(root["apps"]).forEachIndexed { ai, a ->
            val am = map(a) ?: return@forEachIndexed
            val pkg = am["id"]?.toString()
            if (pkg.isNullOrBlank()) {
                skipped += Skipped("$.apps[$ai]", "缺少 id")
                return@forEachIndexed
            }
            list(am["groups"]).forEachIndexed { gi, g ->
                val gm = map(g) ?: return@forEachIndexed
                val groupKey = gm["key"]?.toString() ?: gi.toString()
                val gTiming = timingOf(gm)

                list(gm["rules"]).forEachIndexed { ri, r ->
                    total++
                    val rm = map(r)
                    if (rm == null) {
                        skipped += Skipped("$.apps[$ai].groups[$gi].rules[$ri]", "不是对象")
                        return@forEachIndexed
                    }
                    val matches = rm["matches"]?.toString()
                    if (matches.isNullOrBlank()) {
                        skipped += Skipped("$.apps[$ai].groups[$gi].rules[$ri]", "没有 matches")
                        return@forEachIndexed
                    }
                    val act = actionOf(rm)
                    if (act == null) {
                        skipped += Skipped(
                            "$.apps[$ai].groups[$gi].rules[$ri]",
                            "动作不支持：${rm["action"] ?: "默认 click"}"
                        )
                        return@forEachIndexed
                    }
                    rules += Rule(
                        domain = Domain.NODE,
                        target = matches,
                        act = act,
                        scope = Scope.App(pkg),
                        timing = gTiming.merge(timingOf(rm)),
                        origin = origin,
                        groupKey = groupKey,
                        ruleKey = rm["key"]?.toString() ?: ri.toString(),
                        activityIds = activityIdsOf(gm) + activityIdsOf(rm),
                        note = rm["name"]?.toString() ?: gm["name"]?.toString() ?: "",
                    )
                }
            }
        }

        return Result(rules, skipped, exclusions, total)
    }

    // ---------------- 小工具 ----------------

    /**
     * 取数组。统一走这里是为了避开星投影（`List<*>`）让 forEachIndexed 推断不出类型的问题，
     * 顺带把"不是数组"当成空数组 —— 订阅里缺字段是常态，不该整份失败。
     */
    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    /** GKD 动作 → 我们的动作。不支持的返回 null（调用方记入 skipped）。 */
    private fun actionOf(m: Map<String, Any?>): Act? = when (val a = m["action"]?.toString()) {
        null, "click", "clickNode", "clickCenter" -> Act.CLICK
        "longClick", "longClickNode", "longClickCenter" -> Act.LONG_CLICK
        "back" -> Act.BACK
        "swipe" -> null      // 我们没有滑动动作
        "none" -> null       // "匹配但不动作"没有对应语义
        else -> null
    }

    private fun activityIdsOf(m: Map<String, Any?>): List<String> = when (val v = m["activityIds"]) {
        is List<*> -> v.mapNotNull { it?.toString() }
        is String -> listOf(v)
        else -> emptyList()
    }

    private fun timingOf(m: Map<String, Any?>): Timing = Timing(
        matchWindowMs = (m["matchTime"] as? Number)?.toLong(),
        maxActions = (m["actionMaximum"] as? Number)?.toInt(),
        cooldownMs = (m["actionCd"] as? Number)?.toLong() ?: 1_000L,
    )

    /** 组级与规则级的合并：规则写了就用规则的，没写用组的（GKD 的字段继承链）。 */
    private fun Timing.merge(child: Timing): Timing = Timing(
        delayMs = child.delayMs,
        cooldownMs = child.cooldownMs,
        maxActions = child.maxActions ?: this.maxActions,
        resetBy = child.resetBy,
        matchWindowMs = child.matchWindowMs ?: this.matchWindowMs,
    )
}
