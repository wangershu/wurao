package com.adhammer.rules

import android.content.SharedPreferences
import com.adhammer.rules.model.HitEntry
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.Task
import com.adhammer.util.HLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 规则存储：模块 UI 进程写入、目标 App 的 hook 进程读取。
 *
 * API 102 提供了 getRemotePreferences(group)，由框架负责跨进程同步，
 * 不需要 root 也不需要写目标 App 私有目录 —— 这是相对原版最大的改进。
 *
 * key 约定：
 * - `rules:<pkg>`  → 该包的悟空规则（RulePack 列表 JSON）
 * - `tasks:<pkg>`  → 该包的八戒任务（Task 列表 JSON）
 * - `hosts`        → 沙僧全局 host 列表
 * - `autoskip`     → 八戒智能跳过白名单（包名集合）
 * - `meta:*`       → 订阅元信息
 */
object RuleStore {

    const val GROUP = "adhammer_rules"
    private const val PREFIX_RULES = "rules:"
    private const val PREFIX_TASKS = "tasks:"
    private const val KEY_HOSTS = "hosts"
    private const val KEY_AUTOSKIP = "autoskip"
    private const val PREFIX_HOME = "home:"
    private const val PREFIX_TIPS = "tips:"
    private const val KEY_LOG = "log:recent"
    private const val KEY_META_TIME = "meta:update_time"
    private const val KEY_META_URL = "meta:sub_url"
    private const val KEY_META_MD5 = "meta:zip_md5"

    private val gson = Gson()

    // ---------- 写入（UI 进程） ----------

    fun putRules(prefs: SharedPreferences, pkg: String, packs: List<RulePack>) {
        runCatching {
            prefs.edit().putString(PREFIX_RULES + pkg, gson.toJson(packs)).apply()
        }.onFailure { HLog.e("putRules failed $pkg", it) }
    }

    fun putTasks(prefs: SharedPreferences, pkg: String, tasks: List<Task>) {
        runCatching {
            prefs.edit().putString(PREFIX_TASKS + pkg, gson.toJson(tasks)).apply()
        }.onFailure { HLog.e("putTasks failed $pkg", it) }
    }

    // ---------- 合并写入（手工/AI 生成的规则追加进去，不覆盖订阅规则） ----------

    /**
     * 按 ruleType 归并：同类型的包合并规则列表，新规则追加在后面。
     * 订阅同步是「整包覆盖」，而这里必须「追加」，否则每次生成规则都会清掉订阅内容。
     */
    fun mergeRules(prefs: SharedPreferences, pkg: String, incoming: List<RulePack>): Int {
        if (incoming.isEmpty()) return 0
        val old = getRules(prefs, pkg).toMutableList()
        var added = 0
        incoming.forEach { pack ->
            val idx = old.indexOfFirst { it.ruleType == pack.ruleType && it.name == pack.name }
            if (idx < 0) {
                old.add(pack)
                added += pack.rules.size
            } else {
                val exist = old[idx]
                val merged = exist.rules.toMutableList()
                pack.rules.forEach { r ->
                    val dup = merged.any {
                        it.actionType == r.actionType && it.ad == r.ad && it.adKey == r.adKey
                    }
                    if (!dup) {
                        merged.add(r)
                        added++
                    }
                }
                old[idx] = exist.copy(rules = merged)
            }
        }
        putRules(prefs, pkg, old)
        return added
    }

    /** 合并八戒任务：同名同 Activity 视为一个任务，步骤去重追加 */
    fun mergeTasks(prefs: SharedPreferences, pkg: String, incoming: List<Task>): Int {
        if (incoming.isEmpty()) return 0
        val old = getTasks(prefs, pkg).toMutableList()
        var added = 0
        incoming.forEach { task ->
            val idx = old.indexOfFirst { it.name == task.name && it.act == task.act }
            if (idx < 0) {
                old.add(task)
                added += task.steps.size
            } else {
                val exist = old[idx]
                val merged = exist.steps.toMutableList()
                task.steps.forEach { s ->
                    val dup = merged.any { it.at == s.at && it.t == s.t && it.wv == s.wv && it.vId == s.vId }
                    if (!dup) {
                        merged.add(s)
                        added++
                    }
                }
                old[idx] = exist.copy(steps = merged)
            }
        }
        putTasks(prefs, pkg, old)
        return added
    }

    fun putHosts(prefs: SharedPreferences, hosts: List<String>) {
        runCatching { prefs.edit().putString(KEY_HOSTS, gson.toJson(hosts)).apply() }
    }

    fun putAutoSkip(prefs: SharedPreferences, pkgs: Set<String>) {
        runCatching { prefs.edit().putString(KEY_AUTOSKIP, gson.toArrayList(pkgs)).apply() }
    }

    /** 首页 Activity：at=3（跳过到首页）/ at=4（改写目标）需要「跳到哪」 */
    fun putHome(prefs: SharedPreferences, pkg: String, home: String) {
        runCatching {
            if (home.isBlank()) prefs.edit().remove(PREFIX_HOME + pkg).apply()
            else prefs.edit().putString(PREFIX_HOME + pkg, home).apply()
        }
    }

    fun getHome(prefs: SharedPreferences?, pkg: String): String? =
        runCatching { prefs?.getString(PREFIX_HOME + pkg, null) }.getOrNull()

    /** 跳过成功时的提示文案（原版的 adTips）；空 = 用全局默认文案 */
    fun putTips(prefs: SharedPreferences, pkg: String, tips: String) {
        runCatching {
            if (tips.isBlank()) prefs.edit().remove(PREFIX_TIPS + pkg).apply()
            else prefs.edit().putString(PREFIX_TIPS + pkg, tips).apply()
        }
    }

    fun getTips(prefs: SharedPreferences?, pkg: String): String? =
        runCatching { prefs?.getString(PREFIX_TIPS + pkg, null) }.getOrNull()

    /** hosts 追加去重（手工/分析器新增的域名不能覆盖订阅的） */
    fun mergeHosts(prefs: SharedPreferences, incoming: List<String>): Int {
        val old = getHosts(prefs).toMutableList()
        var added = 0
        incoming.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { h ->
                if (!old.contains(h)) {
                    old.add(h)
                    added++
                }
            }
        if (added > 0) putHosts(prefs, old)
        return added
    }

    /** 拦截日志（hook 进程写，UI 展示） */
    fun putLog(prefs: SharedPreferences, entries: List<HitEntry>) {
        runCatching { prefs.edit().putString(KEY_LOG, gson.toJson(entries)).apply() }
    }

    fun getLog(prefs: SharedPreferences?): List<HitEntry> {
        val json = runCatching { prefs?.getString(KEY_LOG, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            gson.fromJson<List<HitEntry>>(json, object : TypeToken<List<HitEntry>>() {}.type)
        }.getOrElse { emptyList() }
    }

    fun clearLog(prefs: SharedPreferences) {
        runCatching { prefs.edit().remove(KEY_LOG).apply() }
    }

    fun putMeta(prefs: SharedPreferences, url: String, md5: String?, time: String?) {
        runCatching {
            prefs.edit()
                .putString(KEY_META_URL, url)
                .putString(KEY_META_MD5, md5)
                .putString(KEY_META_TIME, time)
                .apply()
        }
    }

    // ---------- 读取（hook 进程） ----------

    fun getRules(prefs: SharedPreferences?, pkg: String): List<RulePack> {
        val json = runCatching { prefs?.getString(PREFIX_RULES + pkg, null) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            gson.fromJson<List<RulePack>>(json, object : TypeToken<List<RulePack>>() {}.type)
        }.getOrElse {
            HLog.w("parse rules failed for $pkg: ${it.message}")
            emptyList()
        }
    }

    fun getTasks(prefs: SharedPreferences?, pkg: String): List<Task> {
        val json = runCatching { prefs?.getString(PREFIX_TASKS + pkg, null) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            gson.fromJson<List<Task>>(json, object : TypeToken<List<Task>>() {}.type)
        }.getOrElse { emptyList() }
    }

    fun getHosts(prefs: SharedPreferences?): List<String> {
        val json = runCatching { prefs?.getString(KEY_HOSTS, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
        }.getOrElse { emptyList() }
    }

    fun getAutoSkip(prefs: SharedPreferences?): Set<String> {
        val json = runCatching { prefs?.getString(KEY_AUTOSKIP, null) }.getOrNull() ?: return emptySet()
        return runCatching {
            gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type)
        }.getOrElse { emptySet() }
    }

    fun getMetaTime(prefs: SharedPreferences?): String? =
        runCatching { prefs?.getString(KEY_META_TIME, null) }.getOrNull()

    fun getMetaUrl(prefs: SharedPreferences?): String? =
        runCatching { prefs?.getString(KEY_META_URL, null) }.getOrNull()

    fun getMetaMd5(prefs: SharedPreferences?): String? =
        runCatching { prefs?.getString(KEY_META_MD5, null) }.getOrNull()

    // ---------- 便捷：Gson 扩展 ----------

    private fun Gson.toArrayList(set: Set<String>): String = toJson(set)
}
