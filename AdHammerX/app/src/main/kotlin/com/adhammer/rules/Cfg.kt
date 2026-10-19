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

    /** 单个应用的开关：true = 这个应用完全不拦（白名单） */
    fun appKey(pkg: String) = "cfg:app:$pkg"

    fun get(prefs: SharedPreferences?, key: String, def: Boolean = true): Boolean =
        runCatching { prefs?.getBoolean(key, def) }.getOrNull() ?: def

    fun put(prefs: SharedPreferences, key: String, value: Boolean) {
        runCatching { prefs.edit().putBoolean(key, value).apply() }
    }
}
