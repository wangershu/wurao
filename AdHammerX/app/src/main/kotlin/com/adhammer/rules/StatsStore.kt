package com.adhammer.rules

import android.content.SharedPreferences
import com.adhammer.util.HLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拦截统计。
 *
 * 和日志（只留最近 40 条明细）不同，这里存的是**累计计数**，用来出图表：
 * - `stat:total`        总命中次数
 * - `stat:day:<yyyyMMdd>`  按天
 * - `stat:kind:<kind>`  按子系统（page / skip / host / view）
 * - `stat:app:<pkg>`    按应用
 *
 * 写入口在 hook 进程（HitLog），读入口在模块 UI 进程，两边共用同一份远程偏好。
 * 计数器都是 Long，读改写；命中频率不高（一次广告一次），不用做批量合并。
 */
object StatsStore {

    const val KIND_PAGE = "page"   // 页面净化：Activity / URL / 组件 / 文件
    const val KIND_VIEW = "view"   // 页面净化：View 精准隐藏
    const val KIND_SKIP = "skip"   // 自动跳过：无障碍任务
    const val KIND_HOST = "host"   // 域名拦截

    private const val KEY_TOTAL = "stat:total"
    private const val KEY_FIRST = "stat:first"      // 首次记录时间，用于「使用天数」
    private const val PREFIX_DAY = "stat:day:"
    private const val PREFIX_KIND = "stat:kind:"
    private const val PREFIX_APP = "stat:app:"

    private val gson = Gson()
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun dayKey(ts: Long = System.currentTimeMillis()): String = dayFmt.format(Date(ts))

    /** 命中一次。从 hook 进程调用，必须足够轻。 */
    @Synchronized
    fun bump(prefs: SharedPreferences?, pkg: String, kind: String) {
        if (prefs == null) return
        runCatching {
            val ed = prefs.edit()
            ed.putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0L) + 1)
            if (prefs.getLong(KEY_FIRST, 0L) == 0L) ed.putLong(KEY_FIRST, System.currentTimeMillis())

            val d = PREFIX_DAY + dayKey()
            ed.putLong(d, prefs.getLong(d, 0L) + 1)

            val k = PREFIX_KIND + kind
            ed.putLong(k, prefs.getLong(k, 0L) + 1)

            if (pkg.isNotBlank()) {
                val a = PREFIX_APP + pkg
                ed.putLong(a, prefs.getLong(a, 0L) + 1)
            }
            ed.apply()
        }.onFailure { HLog.w("bump stats failed: ${it.message}") }
    }

    fun total(prefs: SharedPreferences?): Long =
        runCatching { prefs?.getLong(KEY_TOTAL, 0L) }.getOrNull() ?: 0L

    fun today(prefs: SharedPreferences?): Long =
        runCatching { prefs?.getLong(PREFIX_DAY + dayKey(), 0L) }.getOrNull() ?: 0L

    /** 最近 n 天，从旧到新：[(yyyyMMdd, 次数)] */
    fun recentDays(prefs: SharedPreferences?, n: Int = 7): List<Pair<String, Long>> {
        if (prefs == null) return emptyList()
        val now = System.currentTimeMillis()
        val dayMs = 24 * 3600 * 1000L
        return (n - 1 downTo 0).map { i ->
            val key = dayKey(now - i * dayMs)
            key to (runCatching { prefs.getLong(PREFIX_DAY + key, 0L) }.getOrNull() ?: 0L)
        }
    }

    fun week(prefs: SharedPreferences?): Long = recentDays(prefs, 7).sumOf { it.second }

    /** 按子系统：kind → 次数 */
    fun byKind(prefs: SharedPreferences?): Map<String, Long> {
        if (prefs == null) return emptyMap()
        return listOf(KIND_PAGE, KIND_VIEW, KIND_SKIP, KIND_HOST).associateWith { k ->
            runCatching { prefs.getLong(PREFIX_KIND + k, 0L) }.getOrNull() ?: 0L
        }
    }

    /** 按应用排行（降序） */
    fun byApp(prefs: SharedPreferences?, limit: Int = 10): List<Pair<String, Long>> {
        if (prefs == null) return emptyList()
        val all = runCatching { prefs.all }.getOrNull() ?: return emptyList()
        return all.entries
            .filter { it.key.startsWith(PREFIX_APP) && it.value is Long }
            .map { it.key.removePrefix(PREFIX_APP) to (it.value as Long) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /** 首次使用时间（0 = 还没有过命中） */
    fun firstAt(prefs: SharedPreferences?): Long =
        runCatching { prefs?.getLong(KEY_FIRST, 0L) }.getOrNull() ?: 0L

    /** 清空统计（只清计数，不动规则） */
    fun clear(prefs: SharedPreferences?) {
        if (prefs == null) return
        runCatching {
            val ed = prefs.edit()
            prefs.all.keys.filter {
                it == KEY_TOTAL || it == KEY_FIRST ||
                    it.startsWith(PREFIX_DAY) || it.startsWith(PREFIX_KIND) || it.startsWith(PREFIX_APP)
            }.forEach { ed.remove(it) }
            ed.apply()
        }
    }

    // ---------- 导出：给设置页「数据」用 ----------

    fun export(prefs: SharedPreferences?): String {
        if (prefs == null) return "{}"
        val all = runCatching { prefs.all }.getOrNull() ?: return "{}"
        val map = all.filterKeys {
            it == KEY_TOTAL || it == KEY_FIRST ||
                it.startsWith(PREFIX_DAY) || it.startsWith(PREFIX_KIND) || it.startsWith(PREFIX_APP)
        }
        return gson.toJson(map)
    }

    fun import(prefs: SharedPreferences, json: String): Int {
        val map = runCatching {
            gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
        }.getOrNull() ?: return 0
        val ed = prefs.edit()
        var n = 0
        map.forEach { (k, v) ->
            val num = (v as? Number)?.toLong() ?: return@forEach
            ed.putLong(k, num)
            n++
        }
        ed.apply()
        return n
    }
}
