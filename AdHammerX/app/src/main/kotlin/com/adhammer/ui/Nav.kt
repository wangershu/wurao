package com.adhammer.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * 极简路由：一个返回栈就够用，不引 navigation-compose（省一层依赖与版本坑）。
 *
 * 底部四个主 tab 各自一个独立栈根，切换 tab 时重置栈，
 * 保证「应用 → 详情 → 编辑规则」逐级返回时状态不丢。
 *
 * 结构对齐参考项目 HyperIsland：主页 / 应用 / 统计 / 设置 四个根页，
 * 设置下面再挂若干二级子页。
 */
sealed interface Dest {

    // ---- 主页 ----
    data object Home : Dest

    // ---- 应用管理 ----
    data object Apps : Dest
    data class AppDetail(val pkg: String) : Dest
    /** index = -1 表示新建 */
    data class RuleEdit(val pkg: String, val ruleType: Int, val index: Int) : Dest
    data class HomeAct(val pkg: String) : Dest
    /** 沙僧域名（全局，不属于某个 App） */
    data object Hosts : Dest
    /** 造规则：APK 分析器 / 规则工厂 */
    data object Analyzer : Dest
    data object Factory : Dest

    // ---- 统计 ----
    data object Stats : Dest
    data object Logs : Dest

    // ---- 设置 ----
    data object Settings : Dest
    data object SettingsPurify : Dest       // 净化行为：三个子系统的开关
    data object SettingsAppearance : Dest   // 外观
    data object SettingsSubscription : Dest // 订阅与规则源
    data object SettingsBackup : Dest       // 备份与恢复
    data object SettingsAdvanced : Dest     // 高级
    data object SettingsHelp : Dest         // 使用帮助
    data object About : Dest
}

object Navigator {

    /** 底部导航的根栈位 */
    enum class Tab { HOME, APPS, STATS, SETTINGS }

    private val stack = mutableStateListOf<Dest>(Dest.Home)

    var tab: Tab = Tab.HOME
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

    /** 跨 tab 跳转（比如从统计页跳到某个应用的详情） */
    fun jump(dest: Dest, t: Tab) {
        tab = t
        stack.clear()
        stack.add(rootOf(t))
        stack.add(dest)
    }

    private fun rootOf(t: Tab) = when (t) {
        Tab.HOME -> Dest.Home
        Tab.APPS -> Dest.Apps
        Tab.STATS -> Dest.Stats
        Tab.SETTINGS -> Dest.Settings
    }
}
