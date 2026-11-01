package com.wurao.core

import java.util.EnumSet

/**
 * 风险级能力的按 App 开关。
 *
 * **默认全部关闭。** 理由是你自己提的那条：拦掉广告 SDK 的初始化，有可能导致 App 自己起不来。
 * 所以这一层必须是"你主动决定哪些 App 值得冒这个险"，而不是模块替你决定。
 *
 * 安全级（点跳过 / 藏控件 / 关弹窗 / 去摇一摇）不归这里管 —— 它们默认开，最坏只是没生效。
 */
object RiskGate {

    enum class Kind(val label: String) {
        /** 拦掉整个广告页面 / 组件不让打开 */
        COMPONENT("拦广告页面"),

        /** 广告域名解析到本地，请求不出门 */
        NET("不让广告联网"),

        /** 让广告 SDK 起不来（零规则、抗改版，但可能连累 App） */
        SDK("去掉广告 SDK"),
        ;

        companion object {
            fun fromKey(key: String): Kind? = entries.firstOrNull { it.name == key }
        }
    }

    /** pkg → 已开启的能力 */
    private val opened = HashMap<String, MutableSet<Kind>>()

    fun enable(pkg: String, kind: Kind) {
        opened.getOrPut(pkg) { EnumSet.noneOf(Kind::class.java) }.add(kind)
    }

    fun disable(pkg: String, kind: Kind) {
        opened[pkg]?.remove(kind)
    }

    fun isEnabled(pkg: String, kind: Kind): Boolean = opened[pkg]?.contains(kind) == true

    /** 某个应用开了哪些风险能力（界面显示用）。 */
    fun openedOf(pkg: String): Set<Kind> = opened[pkg]?.toSet() ?: emptySet()

    /** 一键退回：把这个应用的风险能力全关掉（出问题时的救命开关）。 */
    fun disableAll(pkg: String) {
        opened.remove(pkg)
    }

    fun anyEnabled(pkg: String): Boolean = opened[pkg]?.isNotEmpty() == true

    /** 调试/排障：当前开了风险能力的包。 */
    fun snapshot(): Map<String, Set<Kind>> = opened.mapValues { it.value.toSet() }
}
