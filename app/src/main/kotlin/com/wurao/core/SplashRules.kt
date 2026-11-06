package com.wurao.core

import android.content.Context
import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.store.RuleStore

/**
 * 内置 GKD 开屏规则集（官方订阅 v186）：全局「开屏广告」组 + 各 App 专属开屏组。
 *
 * 预处理成扁平 TSV（pkg / act / matches / note），App 侧直接构造 Rule，
 * 不走 Json5 解析器（实测大订阅会漏解析）。subsId=-1 与用户导入的订阅区分；
 * RuleStore.upsert 按 target hash 幂等，重复导入不会翻倍。
 */
object SplashRules {
    const val SUBS_ID = -1L
    private const val ASSET = "gkd_splash.tsv"

    fun alreadyImported(): Boolean = RuleStore.all().any {
        (it.origin as? Origin.Subscription)?.subsId == SUBS_ID
    }

    fun import(ctx: Context): String {
        val text = runCatching {
            ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return "读不到内置开屏规则"
        val origin = Origin.Subscription(SUBS_ID)
        var n = 0
        RuleStore.beginBatch()
        try {
            for (line in text.lineSequence()) {
                val c = line.split('\t')
                if (c.size < 3) continue
                val act = Act.fromKey(c[1]) ?: continue
                val target = c[2]
                if (target.isBlank()) continue
                RuleStore.upsert(
                    Rule(
                        domain = Domain.NODE,
                        target = target,
                        act = act,
                        scope = if (c[0] == "*") Scope.Global else Scope.App(c[0]),
                        origin = origin,
                        groupKey = "splash",
                        ruleKey = target.hashCode().toString(),
                        note = c.getOrNull(3) ?: "开屏广告",
                    )
                )
                n++
            }
        } finally {
            RuleStore.endBatch()
        }
        return "内置开屏规则 $n 条"
    }
}
