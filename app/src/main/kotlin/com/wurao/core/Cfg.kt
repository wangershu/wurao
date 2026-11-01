package com.wurao.core

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

    fun encodeList(items: Collection<String>): String = items.joinToString(",")

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
