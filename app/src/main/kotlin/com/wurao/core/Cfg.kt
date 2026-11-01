package com.wurao.core

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope

/**
 * 配置通道的键名 —— **UI 进程与 hook 进程共用这一份**，改键名两边要一起改。
 *
 * 通道用的是 libxposed 的远程偏好：UI 侧按普通 SharedPreferences 写，
 * hook 侧用 `getRemotePreferences(GROUP)` 读。**只能 UI → hook 单向**；
 * hook 侧要回传数据（命中记录）是另一个方向的通道，另说。
 */
object Cfg {

    const val GROUP = "wurao_cfg"

    /** 风险级开关：`risk:<包名>` → 逗号分隔的能力名，如 "COMPONENT,NET"。没这个键 = 全关。 */
    fun riskKey(pkg: String): String = "risk:$pkg"

    /** 全局暂停（一键全停）。 */
    const val KEY_PAUSED = "paused"

    /**
     * 被停用的规则 id（逗号分隔）—— **排错的出口**。
     * 你在命中记录里点一下"停用"，这条规则就不再执行，但**不会被删除**
     * （删了就找不回来了）。
     */
    const val KEY_STOPPED = "stopped"

    /** 某个应用被排除（全局规则对它不生效）。 */
    fun excludeKey(pkg: String): String = "exclude:$pkg"

    /** 自动分析勾选后要拦的组件：`components:<包名>` → 逗号分隔的组件全名。 */
    fun componentsKey(pkg: String): String = "components:$pkg"

    /** 用户自己加的规则（检视层抓的、或手写的）。 */
    const val KEY_USER_RULES = "user_rules"

    fun encodeList(items: Collection<String>): String = items.joinToString(",")

    /**
     * 规则编成文本送过通道。格式：`包名\t动作\t选择器\t备注\t是否启用`。
     *
     * 只带最小必要字段：域固定是控件（NODE），来源是用户（User）——
     * 通道里多带一个字段就多一处可能对不上的地方。
     */
    fun encodeRules(rules: List<Rule>): String = rules.joinToString("\n") { r ->
        val pkg = when (val s = r.scope) {
            is Scope.App -> s.pkg
            is Scope.Component -> s.pkg
            Scope.Global -> "*"
        }
        // 用 key 不用枚举名：通道两头只认 key（也是 GKD 里的动作名），
        // 写成 HIDE 会在解码时找不到，表现为"加了规则但没生效"
        listOf(esc(pkg), r.act.key, esc(r.target), esc(r.note), r.enabled.toString())
            .joinToString("\t")
    }

    /**
     * 解回规则。认不出的行跳过；**动作非法的一律丢弃**（宁可这条不生效，
     * 也不能让一条瞎写的规则去乱点）。
     */
    fun decodeRules(text: String?): List<Rule> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            val pkg = p[0]
            val act = Act.fromKey(p[1]) ?: return@mapNotNull null
            val target = p[2]
            val note = p[3]
            val enabled = p.getOrNull(4)?.toBoolean() ?: true
            if (target.isBlank()) return@mapNotNull null
            Rule(
                domain = Domain.NODE,
                target = target,
                act = act,
                scope = if (pkg == "*") Scope.Global else Scope.App(pkg),
                origin = Origin.User,
                groupKey = GROUP_USER,
                ruleKey = note.hashCode().toString(),
                note = note,
                enabled = enabled,
            ).takeIf { it.validate().isEmpty() }
        }.toList()
    }

    /** 用户自己加的规则都归到这个组，界面上按组整批删除。 */
    const val GROUP_USER = "mine"

    private fun esc(s: String): String = s.replace("\t", " ").replace("\n", " ")

    fun decodeList(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** 把能力集合序列化（UI 侧写、hook 侧读）。 */
    fun encodeKinds(kinds: Set<RiskGate.Kind>): String =
        kinds.joinToString(",") { it.name }

    /** 反序列化。认不出的名字直接丢掉 —— 宁可少开一项，也不要乱开风险级能力。 */
    fun decodeKinds(raw: String?): Set<RiskGate.Kind> =
        raw?.split(',')
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?.mapNotNull { RiskGate.Kind.fromKey(it) }
            ?.toSet()
            ?: emptySet()
}
