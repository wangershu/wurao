package com.adhammer.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adhammer.rules.Cfg
import com.adhammer.rules.Jsonx
import com.adhammer.rules.RuleStore
import com.adhammer.rules.StatsStore
import com.adhammer.rules.Subscriber
import com.adhammer.rules.model.HitEntry
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.Task
import com.adhammer.util.HLog
import com.google.gson.reflect.TypeToken
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI 全局状态 + 规则读写。
 *
 * 规则读写统一走「本地缓存 → 推远程偏好」两步：
 * 本地缓存保证没激活框架时也能查看/编辑，远程偏好让目标进程的 hook 立刻读到。
 */
object UiState {

    /** 模块是否已激活 */
    var active by mutableStateOf(false)
        private set

    var framework by mutableStateOf("未连接")
        private set

    /** 远程偏好（hook 进程读的那份）；未激活时为 null */
    var remote: SharedPreferences? by mutableStateOf(null)
        private set

    /** 本地缓存偏好 */
    var local: SharedPreferences? = null
        private set

    var syncing by mutableStateOf(false)
        private set

    var lastMessage by mutableStateOf("")
        private set

    var lastSyncTime by mutableStateOf("")
        private set

    /** 已同步的应用：包名 → 规则条数 */
    var appIndex by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set

    var hostCount by mutableStateOf(0)
        private set

    var hosts by mutableStateOf<List<String>>(emptyList())
        private set

    var taskCount by mutableStateOf(0)
        private set

    /** 包名 → 应用名（异步填充，用于列表展示） */
    var appLabels by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 悬浮窗抓取的布局快照，交给「规则工厂」加工 */
    var pendingSnapshot by mutableStateOf("")
        private set

    /** 用户在文件选择器里选的 APK（已复制到 cacheDir，供分析器用） */
    var pendingApk by mutableStateOf<String?>(null)
        private set

    // ---------- 全局开关 ----------

    var pause by mutableStateOf(false)
        private set

    var wuKongOn by mutableStateOf(true)
        private set

    var baJieOn by mutableStateOf(true)
        private set

    var shaSengOn by mutableStateOf(true)
        private set

    /** 最近拦截记录（hook 进程写回来的） */
    var logs by mutableStateOf<List<HitEntry>>(emptyList())
        private set

    // ---------- 统计 ----------

    var statTotal by mutableStateOf(0L)
        private set

    var statToday by mutableStateOf(0L)
        private set

    var statWeek by mutableStateOf(0L)
        private set

    /** 近 7 日：(yyyyMMdd, 次数)，从旧到新 */
    var statDays by mutableStateOf<List<Pair<String, Long>>>(emptyList())
        private set

    /** 按子系统：page / view / skip / host */
    var statKinds by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** 应用排行（降序） */
    var statApps by mutableStateOf<List<Pair<String, Long>>>(emptyList())
        private set

    private var service: XposedService? = null

    fun init(context: Context) {
        local = context.getSharedPreferences("adhammer_local", Context.MODE_PRIVATE)
        lastSyncTime = RuleStore.getMetaTime(local) ?: ""
        // 八戒在模块进程里跑，拿不到 XposedModule 的远程偏好，这里给日志一个本地兜底
        com.adhammer.hook.HitLog.fallback = { local }
        refreshFlags()
        refreshIndex()
        bindService()
    }

    private fun refreshFlags() {
        val p = local
        pause = Cfg.get(p, Cfg.PAUSE, false)
        wuKongOn = Cfg.get(p, Cfg.WU_KONG, true)
        baJieOn = Cfg.get(p, Cfg.BA_JIE, true)
        shaSengOn = Cfg.get(p, Cfg.SHA_SENG, true)
    }

    /** 切换总开关：暂停 / 悟空 / 八戒 / 沙僧 */
    fun setFlag(key: String, on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, key, on)
        refreshFlags()
        commit()
    }

    fun refreshLogs() {
        logs = RuleStore.getLog(local).asReversed()
        refreshStats()
    }

    fun refreshStats() {
        val p = local ?: return
        statTotal = StatsStore.total(p)
        statToday = StatsStore.today(p)
        statWeek = StatsStore.week(p)
        statDays = StatsStore.recentDays(p, 7)
        statKinds = StatsStore.byKind(p)
        statApps = StatsStore.byApp(p, 8)
    }

    /** 清空统计（不动规则与日志） */
    fun clearStats() {
        StatsStore.clear(local)
        remote?.let { StatsStore.clear(it) }
        refreshStats()
    }

    fun clearLogs() {
        val prefs = local ?: return
        RuleStore.clearLog(prefs)
        remote?.let { runCatching { RuleStore.clearLog(it) } }
        logs = emptyList()
    }

    // ---------- 应用级开关 ----------

    /** 该应用是否被单独关掉（true = 白名单，完全不拦） */
    fun appDisabled(pkg: String): Boolean = Cfg.get(local, Cfg.appKey(pkg), false)

    fun setAppDisabled(pkg: String, off: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.appKey(pkg), off)
        commit()
    }

    /** 清空某个应用的全部规则与任务 */
    fun clearApp(pkg: String) {
        val prefs = local ?: return
        runCatching {
            prefs.edit()
                .remove("rules:$pkg")
                .remove("tasks:$pkg")
                .remove("home:$pkg")
                .apply()
        }
        commit()
    }

    // ---------- 规则导入 / 导出 ----------

    /** 导出全部规则、任务、首页配置、hosts 与统计 */
    fun exportJson(): String {
        val src = local ?: return "{}"
        val all = runCatching { src.all }.getOrNull() ?: return "{}"
        val keep = all.filterKeys { k ->
            k.startsWith("rules:") || k.startsWith("tasks:") || k.startsWith("home:") ||
                k.startsWith("stat:") || k == "hosts"
        }
        return Jsonx.toJson(keep) ?: "{}"
    }

    /** 导入：只吃认识的 key，返回导入条数 */
    fun importJson(json: String): Int {
        val prefs = local ?: return 0
        val ed = prefs.edit()
        var n = 0

        // 规则类都是 String
        val map = runCatching {
            Jsonx.gson.fromJson<Map<String, String>>(
                json, object : TypeToken<Map<String, String>>() {}.type
            )
        }.getOrNull() ?: return 0
        map.forEach { (k, v) ->
            if (k.startsWith("rules:") || k.startsWith("tasks:") ||
                k.startsWith("home:") || k == "hosts"
            ) {
                ed.putString(k, v)
                n++
            }
        }

        // 统计是 Long
        val nums = runCatching {
            Jsonx.gson.fromJson<Map<String, Any>>(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )
        }.getOrNull()
        nums?.forEach { (k, v) ->
            val l = (v as? Number)?.toLong()
            if (k.startsWith("stat:") && l != null) {
                ed.putLong(k, l)
                n++
            }
        }

        ed.apply()
        commit()
        return n
    }

    fun setSnapshot(text: String) {
        pendingSnapshot = text
    }

    fun updatePendingApk(path: String?) {
        pendingApk = path
    }

    /** 批量填充应用名（列表滚动时按需调用） */
    fun ensureLabels(context: Context, pkgs: List<String>) {
        val pm = context.packageManager
        val missing = pkgs.filter { !appLabels.containsKey(it) }
        if (missing.isEmpty()) return
        val added = HashMap<String, String>()
        missing.forEach { pkg ->
            val name = runCatching {
                pm.getApplicationInfo(pkg, 0)?.let { pm.getApplicationLabel(it).toString() }
            }.getOrNull()
            added[pkg] = if (name.isNullOrBlank() || name == pkg) pkg else name
        }
        appLabels = appLabels + added
    }

    fun labelOf(pkg: String): String = appLabels[pkg] ?: pkg

    // ==================== 服务绑定 ====================

    private fun bindService() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    active = true
                    framework = runCatching { "${s.frameworkName} ${s.frameworkVersion}" }
                        .getOrDefault("LSPosed")
                    remote = runCatching { s.getRemotePreferences(RuleStore.GROUP) }.getOrNull()
                    HLog.i("service bound: $framework")
                    remote?.let { pushLocalToRemote(it) }
                }

                override fun onServiceDied(s: XposedService) {
                    active = false
                    framework = "未连接"
                    remote = null
                    HLog.w("service died")
                }
            })
        }.onFailure { HLog.w("bind service failed: ${it.message}") }
    }

    private fun pushLocalToRemote(dst: SharedPreferences) {
        val src = local ?: return
        val all = runCatching { src.all }.getOrNull() ?: return
        val ed = dst.edit()
        all.forEach { (k, v) -> if (v is String) ed.putString(k, v) }
        runCatching { ed.apply() }
    }

    // ==================== 订阅 ====================

    suspend fun sync(context: Context, url: String) = withContext(Dispatchers.IO) {
        if (syncing) return@withContext
        syncing = true
        lastMessage = "正在同步…"
        val result = Subscriber.sync(local!!, url, context.cacheDir)
        syncing = false
        lastMessage = result.message
        lastSyncTime = RuleStore.getMetaTime(local) ?: ""
        remote?.let { pushLocalToRemote(it) }
        refreshIndex()
        HLog.i("sync result: ${result.message}")
    }

    private fun refreshIndex() {
        val src = local ?: return
        val all = runCatching { src.all }.getOrNull() ?: return
        val list = ArrayList<Pair<String, Int>>()
        all.keys.filter { it.startsWith("rules:") }.forEach { key ->
            val pkg = key.removePrefix("rules:")
            val packs = RuleStore.getRules(src, pkg)
            val n = packs.sumOf { it.rules.size }
            if (n > 0) list.add(pkg to n)
        }
        appIndex = list.sortedByDescending { it.second }
        hosts = RuleStore.getHosts(src)
        hostCount = hosts.size
        logs = RuleStore.getLog(src).asReversed()
        refreshStats()
        var t = 0
        all.keys.filter { it.startsWith("tasks:") }.forEach { key ->
            t += RuleStore.getTasks(src, key.removePrefix("tasks:")).size
        }
        taskCount = t
    }

    // ==================== 规则 CRUD ====================

    fun packsOf(pkg: String): List<RulePack> = RuleStore.getRules(local, pkg)

    fun tasksOf(pkg: String): List<Task> = RuleStore.getTasks(local, pkg)

    /** 某包某类规则的全部条目 */
    fun rulesOf(pkg: String, ruleType: Int): List<RuleItem> =
        packsOf(pkg).filter { it.ruleType == ruleType }.flatMap { it.rules }

    /**
     * 新增或更新一条规则。
     *
     * index 是「该 ruleType 下跨包合并后的序号」（和列表展示顺序一致），
     * 越界 = 追加到「本地编辑」包，这样订阅包不会被改坏。
     */
    fun saveRule(pkg: String, ruleType: Int, index: Int, item: RuleItem): Boolean {
        val prefs = local ?: return false
        val packs = RuleStore.getRules(prefs, pkg).toMutableList()
        val stored = item.copy(pkg = pkg, ruleType = ruleType)

        val pos = locate(packs, ruleType, index)
        if (pos != null) {
            val (pi, ri) = pos
            val rules = packs[pi].rules.toMutableList().apply { this[ri] = stored }
            packs[pi] = packs[pi].copy(rules = rules)
        } else {
            var pi = packs.indexOfFirst { it.ruleType == ruleType && it.name == LOCAL_PACK }
            if (pi < 0) {
                packs.add(RulePack(isEnable = true, name = LOCAL_PACK, pkg = pkg, ruleType = ruleType))
                pi = packs.lastIndex
            }
            packs[pi] = packs[pi].copy(rules = packs[pi].rules + stored)
        }
        RuleStore.putRules(prefs, pkg, packs)
        commit()
        return true
    }

    /** 把「某类规则的第 index 条」定位到 (第几个包, 包内第几条) */
    private fun locate(packs: List<RulePack>, ruleType: Int, index: Int): Pair<Int, Int>? {
        var cursor = index
        for (i in packs.indices) {
            if (packs[i].ruleType != ruleType) continue
            if (cursor in packs[i].rules.indices) return i to cursor
            cursor -= packs[i].rules.size
        }
        return null
    }

    fun deleteRule(pkg: String, ruleType: Int, index: Int) {
        val prefs = local ?: return
        val packs = RuleStore.getRules(prefs, pkg).toMutableList()
        // 先在本地编辑包里按序号删；规则可能来自订阅包，所以要跨包定位
        var cursor = index
        for (i in packs.indices) {
            if (packs[i].ruleType != ruleType) continue
            if (cursor in packs[i].rules.indices) {
                val rules = packs[i].rules.toMutableList().apply { removeAt(cursor) }
                packs[i] = packs[i].copy(rules = rules)
                break
            }
            cursor -= packs[i].rules.size
        }
        RuleStore.putRules(prefs, pkg, packs.filter { it.rules.isNotEmpty() })
        commit()
    }

    fun toggleRule(pkg: String, ruleType: Int, index: Int, enable: Boolean) {
        val item = rulesOf(pkg, ruleType).getOrNull(index) ?: return
        saveRule(pkg, ruleType, index, item.copy(isEnable = enable))
    }

    /** 手工/AI 生成的规则：追加，不覆盖订阅 */
    fun saveGenerated(pkg: String, packs: List<RulePack>, tasks: List<Task>): String {
        if (pkg.isBlank()) return "包名不能为空"
        val prefs = local ?: return "本地存储未初始化"
        val n1 = RuleStore.mergeRules(prefs, pkg, packs)
        val n2 = RuleStore.mergeTasks(prefs, pkg, tasks)
        commit()
        HLog.i("saved generated rules: $pkg rules=$n1 steps=$n2")
        return "已保存：规则 $n1 条 / 步骤 $n2 步 → $pkg"
    }

    /** 分析器产出的规则：整批写入（同样走 merge，不动订阅规则） */
    fun saveAnalyzed(pkg: String, packs: List<RulePack>, newHosts: List<String>): String {
        val prefs = local ?: return "本地存储未初始化"
        val n = RuleStore.mergeRules(prefs, pkg, packs)
        var nh = 0
        if (newHosts.isNotEmpty()) {
            nh = RuleStore.mergeHosts(prefs, newHosts)
        }
        commit()
        return "已保存 ${packs.sumOf { it.rules.size }} 条规则（新增 $n）+ $nh 个域名"
    }

    // ==================== 沙僧 hosts ====================

    fun addHost(host: String) {
        val h = host.trim().removePrefix("127.0.0.1 ").trim()
        if (h.isBlank() || hosts.contains(h)) return
        saveHosts(hosts + h)
    }

    fun removeHost(host: String) = saveHosts(hosts - host)

    private fun saveHosts(list: List<String>) {
        val prefs = local ?: return
        RuleStore.putHosts(prefs, list)
        commit()
    }

    // ==================== 首页 Activity（at=3/4 用） ====================

    fun homeOf(pkg: String): String = RuleStore.getHome(local, pkg) ?: ""

    fun setHome(pkg: String, activity: String) {
        val prefs = local ?: return
        RuleStore.putHome(prefs, pkg, activity.trim())
        commit()
    }

    // ==================== 作用域 ====================

    fun requestScope(pkg: String) {
        val listener = object : XposedService.OnScopeEventListener {}
        runCatching { service?.requestScope(listOf(pkg), listener) }
            .onFailure { HLog.w("requestScope failed: ${it.message}") }
    }

    /** 写本地之后的统一收尾：推远程 + 刷新索引 */
    private fun commit() {
        remote?.let { pushLocalToRemote(it) }
        refreshIndex()
    }

    const val LOCAL_PACK = "本地编辑"

    /** 规则类型展示名 */
    fun typeName(ruleType: Int): String = when (ruleType) {
        RuleTypes.WU_KONG_RULE -> "通用"
        RuleTypes.WU_KONG_URL -> "URL"
        RuleTypes.WU_KONG_VIEW -> "View"
        RuleTypes.WU_KONG_COMPONENT -> "组件"
        RuleTypes.WU_KONG_REPLACE -> "文件"
        RuleTypes.BA_JIE_TASK -> "任务"
        else -> "其他"
    }
}
