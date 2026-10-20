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

    /** 沙僧按应用的域名：pkg -> 只对它生效的域名列表 */
    var appHosts by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    /** 八戒自动跳过白名单（订阅里的 ba_jie_auto_skip.txt，3000+ 应用） */
    var autoSkip by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 用户自己加进「自动跳过」的应用（原版可手工添加） */
    var mySkips by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 用户明确排除的应用：即使订阅名单里有也不跳 */
    var skipBlock by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 弹框关键词：常见弹框自动关闭用的词表 */
    var popWords by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 规则版本号。
     *
     * 规则读的是 SharedPreferences（不是 Compose state），写完之后列表页不会自己重组。
     * 每次 commit() 都 +1，页面把它放进 remember 的 key 里就能跟着刷新。
     */
    var revision by mutableStateOf(0)
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

    // ---------- 安装状态 ----------

    /** 设备上已安装的包名集合（Android 11+ 需要 QUERY_ALL_PACKAGES 才能看全） */
    var installed by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 每个应用「有没有启用中的规则/任务」：pkg -> 启用条数 */
    var enabledPerApp by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** 首页「已启用规则」：已安装 + 未单独关掉 + 至少一条启用规则的应用数 */
    val ruleAppCount: Int
        get() = enabledPerApp.count { (pkg, n) ->
            n > 0 && !appDisabled(pkg) && (installed.isEmpty() || pkg in installed)
        }

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

    /**
     * 刷新「设备上装了哪些包」。
     *
     * 一次性把全部包名捞出来再查，比对着 200 多个规则包名逐个 getApplicationInfo 快得多；
     * 会在 IO 线程调用，别在主线程碰 PackageManager。
     */
    fun refreshInstalled(ctx: Context) {
        val set = runCatching {
            ctx.packageManager.getInstalledPackages(0).mapTo(HashSet()) { it.packageName }
        }.onFailure {
            android.util.Log.w("AdHammer", "getInstalledPackages failed", it)
        }.getOrDefault(emptySet())
        HLog.i("installed=${set.size} sample=${set.take(3).joinToString()}")
        // 「新应用自动跳过」：这次比上次多出来的包，直接并入自己维护的跳过名单
        if (newAppSkip()) {
            val added = set - knownInstalled
            if (added.isNotEmpty() && knownInstalled.isNotEmpty()) {
                val prefs = local
                if (prefs != null) {
                    var n = 0
                    added.forEach { if (Cfg.addTo(prefs, Cfg.SKIPS, it)) n++ }
                    if (n > 0) HLog.i("new apps auto added to skip list: $n")
                }
            }
        }
        knownInstalled = set
        installed = set
        refreshIndex()
    }

    /** 上一次看到的安装列表，用来算「新装了什么」 */
    private var knownInstalled: Set<String> = emptySet()

    fun isInstalled(pkg: String): Boolean = pkg in installed

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

    /** 批量开关：多选里一次写盘 + 一次 commit，不逐条同步 */
    fun setAppsDisabled(pkgs: Collection<String>, off: Boolean) {
        val prefs = local ?: return
        pkgs.forEach { Cfg.put(prefs, Cfg.appKey(it), off) }
        commit()
    }

    /** 清空某个应用的全部规则与任务 */
    fun clearApp(pkg: String) {
        val prefs = local ?: return
        runCatching {
            prefs.edit()
                .remove("rules:$pkg")
                .remove("tasks:$pkg")
                .remove("hosts:$pkg")
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

        // 应用维度：规则 / 任务 / 按应用域名 三种来源的包名并集
        // （以前只扫 rules:，结果「只有八戒任务」或「只有域名」的应用进不了应用列表）
        val pkgs = all.keys.mapNotNull { key ->
            when {
                key.startsWith("rules:") -> key.removePrefix("rules:")
                key.startsWith("tasks:") -> key.removePrefix("tasks:")
                key.startsWith("hosts:") -> key.removePrefix("hosts:")
                else -> null
            }
        }.distinct()

        val list = ArrayList<Pair<String, Int>>()
        val enabled = HashMap<String, Int>()
        val hostsByPkg = HashMap<String, List<String>>()
        var t = 0

        pkgs.forEach { pkg ->
            val packs = RuleStore.getRules(src, pkg)
            val tasks = RuleStore.getTasks(src, pkg)
            val mine = RuleStore.getAppHosts(src, pkg)
            t += tasks.size
            if (mine.isNotEmpty()) hostsByPkg[pkg] = mine

            val total = packs.sumOf { it.rules.size } + tasks.size + mine.size
            if (total > 0) list.add(pkg to total)

            // 只数「启用中」的：包本身开着 + 规则本身开着，加上八戒任务与专属域名
            val n2 = packs.filter { it.isEnable }.sumOf { p -> p.rules.count { r -> r.isEnable } } +
                tasks.count { it.isEnable } + mine.size
            if (n2 > 0) enabled[pkg] = n2
        }

        appIndex = list.sortedByDescending { it.second }
        enabledPerApp = enabled
        hosts = RuleStore.getHosts(src)
        hostCount = hosts.size
        appHosts = hostsByPkg
        autoSkip = RuleStore.getAutoSkip(src)
        mySkips = Cfg.getSet(src, Cfg.SKIPS)
        skipBlock = Cfg.getSet(src, Cfg.SKIP_BLOCK)
        popWords = Cfg.getStr(src, Cfg.POP_WORDS, DEFAULT_POP_WORDS)
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        logs = RuleStore.getLog(src).asReversed()
        refreshStats()
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

    /**
     * 分析器产出的规则：整批写入（同样走 merge，不动订阅规则）。
     *
     * 域名写进「按应用」的那份 —— 分析的是 A 应用，就不该污染全局黑名单。
     */
    fun saveAnalyzed(pkg: String, packs: List<RulePack>, newHosts: List<String>): String {
        val prefs = local ?: return "本地存储未初始化"
        val n = RuleStore.mergeRules(prefs, pkg, packs)
        var nh = 0
        if (newHosts.isNotEmpty()) {
            nh = RuleStore.mergeAppHosts(prefs, pkg, newHosts)
        }
        commit()
        return "已保存 ${packs.sumOf { it.rules.size }} 条规则（新增 $n）+ $nh 个域名（仅对 $pkg 生效）"
    }

    // ==================== 沙僧 hosts ====================

    /** 全局域名（订阅同步，所有应用生效） */
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

    // ---------- 沙僧：按应用的域名 ----------

    fun appHostsOf(pkg: String): List<String> = appHosts[pkg] ?: emptyList()

    /** 支持一次粘多条：空格 / 逗号 / 换行都能分隔，返回新增条数 */
    fun addAppHosts(pkg: String, text: String): Int {
        val incoming = text.split(Regex("[\\s,;]+"))
            .map { it.trim().removePrefix("127.0.0.1 ").trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        if (incoming.isEmpty()) return 0
        val prefs = local ?: return 0
        val n = RuleStore.mergeAppHosts(prefs, pkg, incoming)
        if (n > 0) commit()
        return n
    }

    fun removeAppHost(pkg: String, host: String) {
        val prefs = local ?: return
        RuleStore.putAppHosts(prefs, pkg, appHostsOf(pkg) - host)
        commit()
    }

    // ==================== 八戒任务 CRUD ====================

    /** 新增或更新一条任务（index 越界 = 追加） */
    fun saveTask(pkg: String, index: Int, task: Task): Boolean {
        val prefs = local ?: return false
        val list = RuleStore.getTasks(prefs, pkg).toMutableList()
        val stored = task.copy(pkg = pkg)
        if (index in list.indices) list[index] = stored else list.add(stored)
        RuleStore.putTasks(prefs, pkg, list)
        commit()
        return true
    }

    fun toggleTask(pkg: String, index: Int, enable: Boolean) {
        val item = tasksOf(pkg).getOrNull(index) ?: return
        saveTask(pkg, index, item.copy(isEnable = enable))
    }

    fun deleteTask(pkg: String, index: Int) {
        val prefs = local ?: return
        val list = RuleStore.getTasks(prefs, pkg).toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        RuleStore.putTasks(prefs, pkg, list)
        commit()
    }

    /** 把一条任务整体启用/停用（详情页顶部的总开关） */
    fun setAllTasksEnabled(pkg: String, enable: Boolean) {
        val prefs = local ?: return
        val list = RuleStore.getTasks(prefs, pkg).map { it.copy(isEnable = enable) }
        RuleStore.putTasks(prefs, pkg, list)
        commit()
    }

    // ==================== 首页 Activity（at=3/4 用） ====================

    fun homeOf(pkg: String): String = RuleStore.getHome(local, pkg) ?: ""

    fun setHome(pkg: String, activity: String) {
        val prefs = local ?: return
        RuleStore.putHome(prefs, pkg, activity.trim())
        commit()
    }

    // ==================== 跳过提示文案 ====================

    fun tipsOf(pkg: String): String = RuleStore.getTips(local, pkg) ?: ""

    fun setTips(pkg: String, tips: String) {
        val prefs = local ?: return
        RuleStore.putTips(prefs, pkg, tips.trim())
        commit()
    }

    // ==================== 作用域 ====================

    fun requestScope(pkg: String) {
        val listener = object : XposedService.OnScopeEventListener {}
        runCatching { service?.requestScope(listOf(pkg), listener) }
            .onFailure { HLog.w("requestScope failed: ${it.message}") }
    }

    // ==================== 八戒自动跳过 ====================

    fun autoSkipOn(): Boolean = Cfg.get(local, Cfg.AUTO_SKIP, true)

    fun setAutoSkip(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.AUTO_SKIP, on)
        commit()
    }

    /** true = 所有应用都自动跳过；false = 只对订阅白名单里的应用 */
    fun autoSkipAll(): Boolean = Cfg.get(local, Cfg.AUTO_SKIP_ALL, false)

    fun setAutoSkipAll(all: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.AUTO_SKIP_ALL, all)
        commit()
    }

    // ---------- 自动跳过：名单 ----------

    /** 最终生效的自动跳过名单 = 订阅名单 ∪ 自己加的 − 明确排除的 */
    val skipScope: Set<String> get() = (autoSkip + mySkips) - skipBlock

    fun addSkip(pkg: String): Boolean {
        val prefs = local ?: return false
        Cfg.removeFrom(prefs, Cfg.SKIP_BLOCK, pkg)
        val ok = Cfg.addTo(prefs, Cfg.SKIPS, pkg)
        if (ok) commit()
        return ok
    }

    fun removeSkip(pkg: String) {
        val prefs = local ?: return
        // 订阅名单里的应用没法「删除」，只能加进排除表
        if (pkg in autoSkip) Cfg.addTo(prefs, Cfg.SKIP_BLOCK, pkg)
        Cfg.removeFrom(prefs, Cfg.SKIPS, pkg)
        commit()
    }

    /** 批量加入（应用页全选用） */
    fun addSkips(pkgs: Collection<String>): Int {
        val prefs = local ?: return 0
        var n = 0
        pkgs.forEach {
            Cfg.removeFrom(prefs, Cfg.SKIP_BLOCK, it)
            if (Cfg.addTo(prefs, Cfg.SKIPS, it)) n++
        }
        if (n > 0) commit()
        return n
    }

    // ---------- 新应用自动跳过 ----------

    fun newAppSkip(): Boolean = Cfg.get(local, Cfg.NEW_APP_SKIP, false)

    fun setNewAppSkip(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.NEW_APP_SKIP, on)
        commit()
    }

    // ---------- 弹框拦截 ----------

    fun popupOn(): Boolean = Cfg.get(local, Cfg.POPUP, true)

    fun setPopup(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.POPUP, on)
        commit()
    }

    fun popupUpgrade(): Boolean = Cfg.get(local, Cfg.POPUP_UPGRADE, true)

    fun setPopupUpgrade(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.POPUP_UPGRADE, on)
        commit()
    }

    fun popupTeen(): Boolean = Cfg.get(local, Cfg.POPUP_TEEN, false)

    fun setPopupTeen(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.POPUP_TEEN, on)
        commit()
    }

    fun setPopWords(text: String) {
        val prefs = local ?: return
        Cfg.putStr(prefs, Cfg.POP_WORDS, text)
        commit()
    }

    // ---------- 沙僧代理 ----------

    fun vpnOn(): Boolean = Cfg.get(local, Cfg.VPN, false)

    fun setVpn(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.VPN, on)
        commit()
    }

    fun vpnExclude(): Set<String> = Cfg.getSet(local, Cfg.VPN_EXCLUDE)

    fun setVpnExclude(set: Set<String>) {
        val prefs = local ?: return
        Cfg.putSet(prefs, Cfg.VPN_EXCLUDE, set)
        commit()
    }

    // ---------- 文件替换 ----------

    fun replaceOn(): Boolean = Cfg.get(local, Cfg.REPLACE, false)

    fun setReplace(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.REPLACE, on)
        commit()
    }

    fun replaceAuto(): Boolean = Cfg.get(local, Cfg.REPLACE_AUTO, false)

    fun setReplaceAuto(on: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.REPLACE_AUTO, on)
        commit()
    }

    // ==================== 按应用的子系统开关 ====================

    /** 这个应用是否单独关掉了某个子系统（悟空 / 八戒 / 沙僧） */
    fun subOff(pkg: String, sub: String): Boolean = Cfg.get(local, Cfg.subKey(pkg, sub), false)

    fun setSubOff(pkg: String, sub: String, off: Boolean) {
        val prefs = local ?: return
        Cfg.put(prefs, Cfg.subKey(pkg, sub), off)
        commit()
    }

    /** 写本地之后的统一收尾：推远程 + 刷新索引 + 规则版本号 +1 */
    private fun commit() {
        remote?.let { pushLocalToRemote(it) }
        refreshIndex()
        revision++
    }

    const val LOCAL_PACK = "本地编辑"

    /** 弹框关键词默认值：弹框上「要点的那个按钮」的文案 */
    const val DEFAULT_POP_WORDS =
        "稍后再说 以后再说 暂不升级 暂不更新 下次再说 我知道了 不再提醒 残忍拒绝 任性拒绝"

    /** 「应用升级」弹框要点的按钮 */
    const val UPGRADE_WORDS = "稍后再说 以后再说 暂不升级 暂不更新 下次再说 取消 以后 暂不"

    /** 「青少年模式」弹框要点的按钮 */
    const val TEEN_WORDS = "我知道了 稍后再说 以后再说 暂不开启 取消"

    // ==================== 文件替换 ====================

    /**
     * 全部 rp=20（文件替换）的规则。
     *
     * index 是该包内「文件替换」这一类的扁平序号，
     * 可以直接喂给 [toggleRule] / [deleteRule] / [saveRule]。
     */
    fun replaceRules(): List<ReplaceEntry> =
        appIndex.map { it.first }.flatMap { pkg ->
            val list = rulesOf(pkg, RuleTypes.WU_KONG_REPLACE)
            if (list.isEmpty()) emptyList()
            else list.mapIndexed { i, item -> ReplaceEntry(pkg, i, item) }
        }

    data class ReplaceEntry(val pkg: String, val index: Int, val item: RuleItem)

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
