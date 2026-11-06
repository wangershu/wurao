package com.wurao.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 极简路由：一个返回栈就够用，不引 navigation-compose。
 * 结构照抄大圣净化（AdHammerX）的 Nav.kt，目的地按本项目的页面裁剪。
 *
 * 底部三个主 tab 各自一个独立栈根，切换 tab 时重置栈，
 * 保证「应用 → 详情」逐级返回时状态不丢。
 */
sealed interface Dest {

    // ---- 三个根页 ----
    data object Home : Dest
    data object Apps : Dest
    data object Settings : Dest

    // ---- 应用 ----
    /** 应用详情：风险级开关 + 该应用的规则 */
    data class AppDetail(val pkg: String) : Dest
    /** 自动分析子页面：扫组件挖广告候选 */
    data class AppAnalysis(val pkg: String) : Dest

    // ---- 主页下挂 ----
    /** 命中记录（主页「今日拦截」统计卡的二级页） */
    data object Hits : Dest

    // ---- 设置下挂 ----
    /** 订阅与规则源：导入 + 订阅分组 + 域名表入口 */
    data object Sources : Dest
    /** 域名表：展示 adclose.txt 的全部域名 */
    data object DomainList : Dest
    /** 备份与恢复 */
    data object Backup : Dest
    /** 外观：主题 + 底栏 */
    data object Appearance : Dest
    /** 关于：渐变沉浸页 */
    data object About : Dest
}

object Navigator {

    /** 底部页签 */
    enum class Tab { HOME, APPS, SETTINGS }

    private val stack = mutableStateListOf<Dest>(Dest.Home)

    /** 必须是 Compose state：底栏高亮和 pager 的联动都靠它，普通 var 不会触发重组 */
    var tab by mutableStateOf(Tab.HOME)
        private set

    val current: Dest get() = stack.lastOrNull() ?: Dest.Home

    val canBack: Boolean get() = stack.size > 1

    /** 是否停在根页：是则显示底栏，否则显示二级页（对齐 HyperIsland 的 detail 层） */
    val atRoot: Boolean get() = stack.size == 1

    fun go(dest: Dest) {
        stack.add(dest)
    }

    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    /** 切换到某个底部 tab（重置该 tab 的栈） */
    fun switch(t: Tab) {
        if (tab == t && stack.size == 1) return
        tab = t
        stack.clear()
        stack.add(rootOf(t))
    }

    /** 跨 tab 跳转（比如从主页跳到应用页的详情） */
    fun jump(dest: Dest, t: Tab) {
        tab = t
        stack.clear()
        stack.add(rootOf(t))
        stack.add(dest)
    }

    private fun rootOf(t: Tab) = when (t) {
        Tab.HOME -> Dest.Home
        Tab.APPS -> Dest.Apps
        Tab.SETTINGS -> Dest.Settings
    }
}
