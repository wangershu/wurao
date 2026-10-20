package com.adhammer.hook

import android.content.SharedPreferences
import com.adhammer.rules.Cfg
import com.adhammer.rules.RuleStore
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.Task
import com.adhammer.util.HLog

/**
 * hook 进程侧的运行时配置。
 *
 * 通过 API 102 的 getRemotePreferences 读取模块 UI 写入的规则，
 * 每次读取都做轻量缓存（hook 路径上不重复走 binder）。
 */
object RuntimeCfg {

    private var prefs: SharedPreferences? = null

    /** 全局开关（UI 写在 cfg:* 里） */
    @Volatile var pause: Boolean = false
        private set

    @Volatile var wuKong: Boolean = true
        private set

    @Volatile var shaSeng: Boolean = true
        private set

    /** 文件替换总开关（默认关：替换有风险，要用户显式开） */
    @Volatile var replace: Boolean = false
        private set

    /** 广告文件被应用写回来后自动再替换一次 */
    @Volatile var replaceAuto: Boolean = false
        private set

    fun prefs(): SharedPreferences? = prefs
    private val cache = HashMap<String, List<RulePack>>()
    private val taskCache = HashMap<String, List<Task>>()
    private var hostsCache: List<String> = emptyList()
    private val appHostCache = HashMap<String, List<String>>()
    private var autoSkipCache: Set<String> = emptySet()

    fun init(p: SharedPreferences?) {
        prefs = p
        cache.clear()
        taskCache.clear()
        appHostCache.clear()
        // 首页 Activity 由 UI 写在同一个远程偏好里，这里一起接管
        HomeConfig.attach(p)
        pause = Cfg.get(p, Cfg.PAUSE, false)
        wuKong = Cfg.get(p, Cfg.WU_KONG, true)
        shaSeng = Cfg.get(p, Cfg.SHA_SENG, true)
        replace = Cfg.get(p, Cfg.REPLACE, false)
        replaceAuto = Cfg.get(p, Cfg.REPLACE_AUTO, false)
        hostsCache = RuleStore.getHosts(p)
        autoSkipCache = RuleStore.getAutoSkip(p)
        HLog.d("RuntimeCfg init: hosts=${hostsCache.size} autoSkip=${autoSkipCache.size}")
    }

    /** 取某包的全部规则包 */
    fun packsOf(pkg: String): List<RulePack> =
        cache.getOrPut(pkg) { RuleStore.getRules(prefs, pkg) }

    /** 取某包某类规则的所有条目 */
    fun rulesOf(pkg: String, ruleType: Int): List<RuleItem> =
        packsOf(pkg).filter { it.ruleType == ruleType && it.isEnable }
            .flatMap { it.rules }
            .filter { it.isEnable }

    /** 取某包的八戒任务 */
    fun tasksOf(pkg: String): List<Task> =
        taskCache.getOrPut(pkg) { RuleStore.getTasks(prefs, pkg) }.filter { it.isEnable }

    val hosts: List<String> get() = hostsCache
    val autoSkip: Set<String> get() = autoSkipCache

    /** 沙僧：全局域名 + 该应用专属域名（去重后一起拦） */
    fun hostsFor(pkg: String): List<String> =
        appHostCache.getOrPut(pkg) {
            val mine = RuleStore.getAppHosts(prefs, pkg)
            if (mine.isEmpty()) hostsCache else (hostsCache + mine).distinct()
        }

    /** 该应用是否装了任何东西：悟空规则 / 八戒任务 / 沙僧专属域名，有一个就值得 hook */
    fun hasAnyRule(pkg: String): Boolean =
        packsOf(pkg).isNotEmpty() ||
            RuleStore.getTasks(prefs, pkg).isNotEmpty() ||
            RuleStore.getAppHosts(prefs, pkg).isNotEmpty()

    /** 该应用是否被单独关掉（白名单） */
    fun appOff(pkg: String): Boolean = Cfg.get(prefs, Cfg.appKey(pkg), false)

    /** 该应用的某个子系统是否被单独关掉（悟空 / 八戒 / 沙僧 分级开关） */
    fun subOff(pkg: String, sub: String): Boolean = Cfg.get(prefs, Cfg.subKey(pkg, sub), false)

    /** 规则统计，供日志与自检 */
    fun summary(pkg: String): String {
        val p = packsOf(pkg)
        if (p.isEmpty()) return "no rules"
        return buildString {
            append("pkg=$pkg ")
            p.groupBy { it.ruleType }.forEach { (type, packs) ->
                val name = when (type) {
                    RuleTypes.WU_KONG_RULE -> "rule"
                    RuleTypes.WU_KONG_URL -> "url"
                    RuleTypes.WU_KONG_VIEW -> "view"
                    RuleTypes.WU_KONG_COMPONENT -> "component"
                    RuleTypes.WU_KONG_REPLACE -> "replace"
                    else -> "t$type"
                }
                append("$name=${packs.sumOf { it.rules.size }} ")
            }
        }
    }
}
