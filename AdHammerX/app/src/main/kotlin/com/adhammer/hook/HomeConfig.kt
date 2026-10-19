package com.adhammer.hook

import android.content.SharedPreferences
import com.adhammer.rules.RuleStore

/**
 * 首页 Activity 配置。
 *
 * 订阅规则里 at=3（跳过到首页）/ at=4（改写跳转目标）只给了「广告 Activity」，
 * 并没有给「要跳到哪」。原版把这个信息放在每 App 规则的 home 字段里。
 *
 * 这里单独存一份 `home:<pkg>`，由 UI 让用户为每个 App 指定首页；
 * 没配置时悟空会降级为「直接关闭广告页」，保证规则不会整体失效。
 */
object HomeConfig {

    private const val PREFIX = "home:"
    private val cache = HashMap<String, String?>()

    fun attach(prefs: SharedPreferences?) {
        store = prefs
        cache.clear()
    }

    private var store: SharedPreferences? = null

    fun get(pkg: String): String? {
        cache[pkg]?.let { return it }
        val v = runCatching { store?.getString(PREFIX + pkg, null) }.getOrNull()
        cache[pkg] = v
        return v
    }

    fun put(prefs: SharedPreferences, pkg: String, home: String) {
        runCatching {
            prefs.edit().putString(PREFIX + pkg, home).apply()
            cache[pkg] = home
        }
    }
}
