package com.wurao.core

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope

/**
 * 内置规则：不需要任何订阅、不需要用户配置就能生效的那部分（L1「看见就认」）。
 *
 * 判据照抄 GKD 那两条全局选择器里经得起推敲的细节：
 * - `[text.length<10]` 挡掉「跳过广告可获赠会员」这类营销按钮；
 * - 第二条不带 `visibleToUser` 是**兜底** —— 有些 App 的跳过按钮在 View 侧拿不到可见性，
 *   宁可多判一次，也别整个失效（点击本身有冷却与次数上限兜着，不会乱点）。
 */
object Builtin {

    fun rules(): List<Rule> = listOf(
        Rule(
            domain = Domain.NODE,
            target = """[text*="跳过"][text.length<10][visibleToUser=true]""",
            act = Act.CLICK,
            scope = Scope.Global,
            origin = Origin.Builtin,
            groupKey = "splash",
            ruleKey = "0",
            note = "开屏广告的跳过按钮（要求可见）",
        ),
        Rule(
            domain = Domain.NODE,
            target = """[text*="跳过"][text.length<10]""",
            act = Act.CLICK,
            scope = Scope.Global,
            origin = Origin.Builtin,
            groupKey = "splash",
            ruleKey = "1",
            note = "开屏广告的跳过按钮（不看可见性，兜底）",
        ),
    )
}
