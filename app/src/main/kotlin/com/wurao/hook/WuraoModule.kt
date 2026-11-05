package com.wurao.hook

import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.net.InetAddress

/**
 * LSPosed hook 入口（libxposed api 102 的新 API）。
 *
 * 两条规则都在框架托管的 RemotePreferences 里（无扰 App 一次性落位，不 IPC、不耗电）：
 * - wurao_rules_<pkg>：控件「藏起来」规则 → hook ViewGroup.dispatchDraw → view.visibility = GONE
 * - wurao_domains：域名规则（全局一份）→ hook InetAddress 解析 → 命中返回 0.0.0.0
 * 「点掉」是无障碍那边做的，不在这。
 */
class WuraoModule : XposedModule() {

    @Volatile private var cachedRules = emptyList<String>()
    @Volatile private var lastLoadMs = 0L
    private var currentPkg = ""
    private val hiddenTargets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var domainSet: Set<String> = emptySet()
    @Volatile private var domainsLoaded = false
    @Volatile private var lastDomainMs = 0L

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName

        // ---- 控件隐藏 ----
        runCatching {
            hook(ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java))
                .intercept { chain ->
                    val vg = chain.thisObject as? ViewGroup
                    if (vg != null) hideMatched(vg, pkg)
                    chain.proceed()
                }
        }

        // ---- 域名拦截（DNS 解析层）----
        runCatching {
            hook(InetAddress::class.java.getDeclaredMethod("getByName", String::class.java))
                .intercept { chain ->
                    val host = chain.getArg(0) as? String
                    if (host != null && isBlocked(host)) {
                        logBlocked(host)
                        return@intercept blackhole(host)
                    }
                    chain.proceed()
                }
            hook(InetAddress::class.java.getDeclaredMethod("getAllByName", String::class.java))
                .intercept { chain ->
                    val host = chain.getArg(0) as? String
                    if (host != null && isBlocked(host)) {
                        logBlocked(host)
                        return@intercept arrayOf(blackhole(host))
                    }
                    chain.proceed()
                }
        }.onFailure { Log.w("Wurao", "dns hook failed: ${it.javaClass.simpleName}") }

        Log.i("Wurao", "hooked $pkg")
    }

    // ==================== 控件隐藏 ====================

    private fun hideMatched(vg: ViewGroup, pkg: String) {
        val rules = loadHideRules(pkg)
        if (rules.isEmpty()) return
        currentPkg = pkg
        hideRecursive(vg, rules)
    }

    /** 读本应用的规则（按应用切分，2 秒节流）。 */
    private fun loadHideRules(pkg: String): List<String> {
        val cachedBefore = cachedRules.size
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLoadMs < 2000) return cachedRules
        lastLoadMs = now
        val list = runCatching {
            readRemote("wurao_rules_$pkg").lines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { String(java.util.Base64.getDecoder().decode(line), Charsets.UTF_8) }.getOrNull()
            }
        }.getOrElse { emptyList() }
        cachedRules = list
        if (list.size != cachedBefore) Log.i("Wurao", "rules loaded: ${list.size} for $pkg")
        return cachedRules
    }

    /**
     * 后序遍历：先处理子 View，再检查自己。
     * 命中「叶子」后其祖先不再检查，避免一个大容器把整片界面连带隐藏。
     */
    private fun hideRecursive(v: View, rules: List<String>): Boolean {
        var childHit = false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                // 不提前 return：同屏多个相同项都要隐藏
                if (hideRecursive(v.getChildAt(i), rules)) childHit = true
            }
        }
        // 子树里已经命中，说明目标是更细的控件，这个容器别再动
        if (childHit) return true
        for (target in rules) {
            if (matchesTarget(target, v)) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    v.layoutParams?.let { lp ->
                        runCatching { lp.width = 0; lp.height = 0; v.layoutParams = lp }
                    }
                    if (hiddenTargets.add("$currentPkg:$target"))
                        Log.i("Wurao", "hid $target in $currentPkg")
                }
                return true
            }
        }
        return false
    }

    private val attrRegex = Regex("\\[(text|desc|vid|name)\\*?=\"([^\"]+)\"\\]")

    /**
     * 关系选择器 `A << B` 的含义是「在 A 的后代里找 B」：
     * - **只有右侧 B 才是要隐藏的目标**，左侧 A 是上下文约束；
     * - A 用祖先链验证，避免同名文案在别处误伤。
     * 之前把 A 的属性也当目标，结果 `[vid="bottom_tab_block"] << [desc="视频"]`
     * 直接把整个底栏隐藏了。
     */
    private fun matchesTarget(target: String, v: View): Boolean {
        val selfPart = target.substringAfterLast("<<").trim()
        val containerPart = target.takeIf { it.contains("<<") }
            ?.substringBeforeLast("<<")?.trim()
        val attrs = attrRegex.findAll(selfPart).toList()
        if (attrs.isEmpty()) return false
        if (!attrHits(attrs, v)) return false
        // 容器约束：祖先链里必须找得到 A；拿不到 parent（某些自绘界面）时放宽
        if (!containerPart.isNullOrBlank()) {
            val cAttrs = attrRegex.findAll(containerPart).toList()
            if (cAttrs.isNotEmpty()) {
                var p = v.parent
                var seenParent = false
                var found = false
                while (p is View) {
                    seenParent = true
                    if (attrHits(cAttrs, p)) { found = true; break }
                    p = p.parent
                }
                if (seenParent && !found) return false
            }
        }
        return true
    }

    private fun attrHits(attrs: List<MatchResult>, v: View): Boolean {
        val txt = when (v) {
            is android.widget.TextView -> v.text?.toString() ?: ""
            else -> ""
        }
        val desc = v.contentDescription?.toString() ?: ""
        val vid = runCatching {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else null
        }.getOrNull() ?: ""
        val cls = v.javaClass.name.substringAfterLast('.')
        for (m in attrs) {
            val value = m.groupValues[2]
            val hit = when (m.groupValues[1]) {
                "text" -> txt.isNotEmpty() && (txt == value || txt.contains(value))
                "desc" -> desc.isNotEmpty() && (desc == value || desc.contains(value))
                "vid" -> vid.isNotEmpty() && vid == value
                "name" -> cls == value
                else -> false
            }
            if (hit) return true
        }
        return false
    }

    // ==================== 域名拦截 ====================

    /** 域名表懒加载（全局一份，不按应用切）。 */
    private fun loadDomains(): Set<String> {
        if (domainsLoaded) return domainSet
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDomainMs < 5000) return domainSet
        lastDomainMs = now
        val set = runCatching {
            readRemote("wurao_domains").lines().mapNotNull(::cleanDomain).toHashSet()
        }.getOrDefault(emptySet())
        domainSet = set
        if (set.isNotEmpty()) {
            domainsLoaded = true
            Log.i("Wurao", "domains loaded: ${set.size}")
        }
        return set
    }

    /** 兼容 AdGuard(`||x^`)、hosts(`0.0.0.0 x`)、纯域名三种格式。 */
    private fun cleanDomain(line: String): String? {
        var d = line.trim().lowercase()
        if (d.isEmpty() || d.startsWith("#") || d.startsWith("!")) return null
        if (d.startsWith("||")) d = d.removePrefix("||")
        if (d.startsWith("0.0.0.0 ") || d.startsWith("127.0.0.1 ") || d.startsWith("::1 "))
            d = d.substringAfter(' ').trim()
        d = d.removeSuffix("^").removePrefix("*.").trim()
        if (d.contains("/") || d.contains(" ") || d.contains("*") || !d.contains(".")) return null
        return d
    }

    /** 精确 + 逐级父域后缀匹配。 */
    private fun isBlocked(host: String): Boolean {
        val set = loadDomains()
        if (set.isEmpty()) return false
        val h = host.lowercase().trim().trimEnd('.')
        if (set.contains(h)) return true
        var i = h.indexOf('.')
        while (i >= 0) {
            if (set.contains(h.substring(i + 1))) return true
            i = h.indexOf('.', i + 1)
        }
        return false
    }

    /** 读框架托管的远程文件（写侧是 XposedBridge.writeRemote）。 */
    private fun readRemote(name: String): String = runCatching {
        val pfd = openRemoteFile(name) ?: return ""
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")

    /** 命中后返回不可达地址（不是抛异常，避免应用崩溃或重试风暴）。 */
    private fun blackhole(host: String): InetAddress =
        InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0))

    private fun logBlocked(host: String) {
        if (blockedLogged.add(host)) Log.i("Wurao", "dns blocked $host")
    }
}
