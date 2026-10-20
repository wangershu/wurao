package com.adhammer.rules

import android.content.SharedPreferences

/**
 * 全局开关：暂停 / 悟空 / 八戒 / 沙僧。
 *
 * 存在同一份远程偏好里（`cfg:*`），所以 hook 进程和无障碍服务都能直接读到，
 * 不需要再走一遍 XposedService。
 */
object Cfg {

    const val PAUSE = "cfg:pause"
    const val WU_KONG = "cfg:wukong"
    const val BA_JIE = "cfg:bajie"
    const val SHA_SENG = "cfg:shaseng"

    /** 跳过成功时是否弹提示（原版的 adTips 行为） */
    const val TIPS = "cfg:tips"

    /** 八戒自动跳过：见到「跳过 / 关闭广告」就点（原版的「自动识别广告」） */
    const val AUTO_SKIP = "cfg:autoskip"

    /** 自动跳过的范围：true = 所有应用，false = 只对订阅白名单里的应用 */
    const val AUTO_SKIP_ALL = "cfg:autoskip_all"

    /** 新安装的应用是否自动加入自动跳过列表（原版「新应用自动跳过」） */
    const val NEW_APP_SKIP = "cfg:newappskip"

    /** 常见弹框自动关闭（原版「取消弹框列表」） */
    const val POPUP = "cfg:popup"

    /** 自动点掉「应用升级」弹框（原版「启用跳过应用升级」） */
    const val POPUP_UPGRADE = "cfg:popup_upgrade"

    /** 自动点掉「青少年模式」弹框（原版「启用跳过青少年模式」） */
    const val POPUP_TEEN = "cfg:popup_teen"

    /** 沙僧代理（VpnService）是否开启 */
    const val VPN = "cfg:vpn"

    /** 广告文件被应用恢复后自动重新替换（依赖无障碍检测，原版「自动检测并替换」） */
    const val REPLACE_AUTO = "cfg:replace_auto"

    /** 文件替换总开关（原版「替换功能关闭，可在替换规则页面开启」） */
    const val REPLACE = "cfg:replace"

    /** 单个应用的开关：true = 这个应用完全不拦（白名单） */
    fun appKey(pkg: String) = "cfg:app:$pkg"

    // ---- 字符串型配置 ----

    /** 用户自己维护的「自动跳过」应用名单（空格分隔），和订阅名单取并集 */
    const val SKIPS = "cfg:skips"

    /** 用户明确排除的应用（空格分隔）：即使在订阅名单里也不自动跳过 */
    const val SKIP_BLOCK = "cfg:skipblock"

    /** 弹框关键词（空格分隔），用于「常见弹框自动关闭」 */
    const val POP_WORDS = "cfg:popwords"

    /** 沙僧代理排除的应用（空格分隔）：这些应用不走 VPN */
    const val VPN_EXCLUDE = "cfg:vpn_exclude"

    // ---- 子系统代号：详情页按「悟空 / 八戒 / 沙僧」三块分别开关 ----

    const val SUB_WU_KONG = "wk"
    const val SUB_BA_JIE = "bj"
    const val SUB_SHA_SENG = "ss"

    /** 单个应用下某个子系统的开关：true = 这个应用不装这个子系统 */
    fun subKey(pkg: String, sub: String) = "cfg:off:$sub:$pkg"

    fun get(prefs: SharedPreferences?, key: String, def: Boolean = true): Boolean =
        runCatching { prefs?.getBoolean(key, def) }.getOrNull() ?: def

    fun put(prefs: SharedPreferences, key: String, value: Boolean) {
        runCatching { prefs.edit().putBoolean(key, value).apply() }
    }

    // ---- 字符串型：名单类配置（空格分隔） ----

    fun getStr(prefs: SharedPreferences?, key: String, def: String = ""): String =
        runCatching { prefs?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() } ?: def

    fun putStr(prefs: SharedPreferences, key: String, value: String) {
        runCatching {
            if (value.isBlank()) prefs.edit().remove(key).apply()
            else prefs.edit().putString(key, value.trim()).apply()
        }
    }

    /** 把「空格分隔的字符串」读成集合 */
    fun getSet(prefs: SharedPreferences?, key: String): Set<String> =
        getStr(prefs, key).split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    /** 集合增删后写回；空集合直接删掉这个 key */
    fun putSet(prefs: SharedPreferences, key: String, set: Set<String>) {
        putStr(prefs, key, set.filter { it.isNotBlank() }.joinToString(" "))
    }

    /** 往名单里加一项，返回是否真的新增 */
    fun addTo(prefs: SharedPreferences, key: String, value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false
        val set = getSet(prefs, key).toMutableSet()
        if (!set.add(v)) return false
        putSet(prefs, key, set)
        return true
    }

    /** 从名单里移除一项 */
    fun removeFrom(prefs: SharedPreferences, key: String, value: String) {
        val set = getSet(prefs, key).toMutableSet()
        if (set.remove(value.trim())) putSet(prefs, key, set)
    }
}
