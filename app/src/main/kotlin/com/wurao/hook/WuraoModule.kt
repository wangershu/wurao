package com.wurao.hook

import android.app.Activity
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import com.wurao.core.analyze.RectMatch
import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.inapp.Report
import com.wurao.core.selector.SelectorMatcher
import com.wurao.core.selector.SelectorParser
import com.wurao.inapp.Actor
import com.wurao.inapp.ViewAdapter
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
    private val containerMissLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val blockedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val clickedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reportedHits = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var everResumed = false
    private var backgrounded = false

    @Volatile private var lastRectMs = 0L
    private val rectHits = java.util.concurrent.ConcurrentHashMap<String, View>()
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

        // ---- 自动点按：只在冷启动 / 从后台切回时跑 ----
        // onStop 标记「离开了」，onResume 时只有首次或离开过才触发；
        // 应用内页面切换不会 onStop，所以不会每页都点。
        runCatching {
            hook(Activity::class.java.getDeclaredMethod("onStop"))
                .intercept { chain ->
                    backgrounded = true
                    chain.proceed()
                }
            hook(Activity::class.java.getDeclaredMethod("onResume"))
                .intercept { chain ->
                    val a = chain.thisObject as? Activity
                    val fire = !everResumed || backgrounded
                    if (fire) { everResumed = true; backgrounded = false }
                    val ret = chain.proceed()
                    if (fire && a != null) {
                        val h = Handler(Looper.getMainLooper())
                        // 两次延迟覆盖异步填充的开屏广告
                        h.postDelayed({ runClicks(a, pkg) }, 300)
                        h.postDelayed({ runClicks(a, pkg) }, 1200)
                    }
                    ret
                }
        }.onFailure { Log.w("Wurao", "activity hook failed: ${it.javaClass.simpleName}") }

        Log.i("Wurao", "hooked $pkg")
    }

    /** 命中回传：同一目标每进程只报一次，避免每帧 insert 耗电。 */
    private fun reportHit(pkg: String, target: String, act: Act) {
        if (!reportedHits.add("$pkg:$act:$target")) return
        runCatching {
            Report.hitRaw(
                pkg = pkg,
                key = target,   // hook 侧只有 target；界面按 id 或 target 反查规则
                note = if (act == Act.CLICK) "自动点按" else "藏起来",
                act = act,
                domain = Domain.NODE,
                target = target,
            )
        }
    }

    // ==================== 自动点按 ====================

    /** 点按成功后按配置弹 Toast；配置来自界面推送的 cfg.json，读失败就按默认。 */
    private fun showClickToast(a: Activity) {
        val cfg = runCatching {
            org.json.JSONObject(readRemote(com.wurao.core.Cfg.CFG_FILE))
        }.getOrNull() ?: return
        if (cfg.optString(com.wurao.core.Cfg.KEY_CLICK_TOAST, "true") == "false") return
        val text = cfg.optString(
            com.wurao.core.Cfg.KEY_CLICK_TOAST_TEXT,
            com.wurao.core.Cfg.DEFAULT_CLICK_TOAST_TEXT,
        ).ifBlank { com.wurao.core.Cfg.DEFAULT_CLICK_TOAST_TEXT }
        Log.i("Wurao", "click toast: $text")
        runCatching {
            android.widget.Toast.makeText(a, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 读本应用的 CLICK 规则，行格式 b64("act\ttarget")。开屏才调，无需缓存节流。 */
    private fun loadClickRules(pkg: String): List<Pair<String, String>> = runCatching {
        readRemote("wurao_click_$pkg").lines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val raw = String(java.util.Base64.getDecoder().decode(line), Charsets.UTF_8)
                val i = raw.indexOf('\t')
                if (i > 0) raw.substring(0, i) to raw.substring(i + 1) else "click" to raw
            }.getOrNull()
        }
    }.getOrElse { emptyList() }

    private fun runClicks(a: Activity, pkg: String) {
        val rules = loadClickRules(pkg)
        if (rules.isEmpty()) return
        val root = runCatching { a.window?.decorView }.getOrNull() ?: return
        if (rules.isNotEmpty()) Log.i("Wurao", "click rules loaded: ${rules.size} for $pkg")
        val matcher = SelectorMatcher(ViewAdapter(pkg))
        for ((actKey, target) in rules) {
            val sel = runCatching { SelectorParser(target).parse() }.getOrNull() ?: continue
            val hit = runCatching { matcher.match(sel, root) }.getOrNull() ?: continue
            val act = Act.fromKey(actKey) ?: continue
            if (Actor.perform(act, hit)) {
                if (clickedLogged.add("$pkg:$target")) Log.i("Wurao", "clicked $target in $pkg")
                reportHit(pkg, target, act)
                showClickToast(a)
                // 一次开屏只点一次：兜底规则与精确规则会命中同一个「跳过」按钮
                break
            }
        }
    }

    // ==================== 控件隐藏 ====================

    private fun hideMatched(vg: ViewGroup, pkg: String) {
        // 命中回传通道需要 Context；拿不到也不影响去广告本身
        runCatching { Report.attach(vg.context.applicationContext) }
        val rules = loadHideRules(pkg)
        if (rules.isEmpty()) return
        currentPkg = pkg

        // 带 rect 的规则：全树找跟框选区域最吻合的 View，整块隐藏。
        // 不带 rect 的规则（vid/text/desc）：走原来的属性匹配，行为一字不改。
        val plain = rules.filterNot { rectRegex.containsMatchIn(it) }
        if (plain.isNotEmpty()) hideRecursive(vg, plain)
        val rects = rules.filter { rectRegex.containsMatchIn(it) }
        if (rects.isNotEmpty()) hideByRect(vg, rects)
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

    /** 属性规则（vid/text/desc）：后序遍历命中即 GONE，与旧行为完全一致。 */
    private fun hideRecursive(v: View, rules: List<String>): Boolean {
        var childHit = false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hideRecursive(v.getChildAt(i), rules)) childHit = true
            }
        }
        if (childHit) return true
        for (target in rules) {
            if (matchesTarget(target, v)) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    v.layoutParams?.let { lp ->
                        runCatching { lp.width = 0; lp.height = 0; v.layoutParams = lp }
                    }
                    if (hiddenTargets.add("$currentPkg:$target")) {
                        Log.i("Wurao", "hid $target in $currentPkg")
                        reportHit(currentPkg, target, Act.HIDE)
                    }
                }
                return true
            }
        }
        return false
    }

    /** 从任意 ViewGroup 找到窗口根，再从根收集全部 View（dispatchDraw 传进来的可能只是子树）。 */
    private fun rootOf(v: View): View {
        var p = v
        while (p.parent is View) p = p.parent as View
        return p
    }

    private fun collect(v: View, out: MutableList<View>) {
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    /** 纯几何选 View：IoU 最大且过阈值，隐藏整块（不是只 GONE 一个子 View）。 */
    private fun hideByRect(vg: ViewGroup, rects: List<String>) {
        val now = android.os.SystemClock.elapsedRealtime()
        val allowScan = now - lastRectMs >= 300   // 全树扫描节流
        if (allowScan) lastRectMs = now
        val loc = IntArray(2)
        val views: List<View> by lazy {
            ArrayList<View>(160).also { collect(rootOf(vg), it) }
        }

        for (target in rects) {
            val raw = rectRegex.find(target)?.groupValues?.get(1) ?: continue
            val box = RectMatch.parse(raw) ?: continue

            // 已选中的 View：每帧强制 GONE（列表/TabWidget 会重建或恢复子 View）。
            // 同时校验位置，布局变了就丢弃缓存重新扫描。
            val cached = rectHits[target]
            if (cached != null && cached.isAttachedToWindow) {
                runCatching { cached.getLocationOnScreen(loc) }
                val score = RectMatch.iou(
                    RectMatch.of(loc[0], loc[1], loc[0] + cached.width, loc[1] + cached.height), box
                )
                if (score >= 0.55f) {
                    forceGone(cached, target)
                    continue
                }
                rectHits.remove(target)
            }
            if (!allowScan) continue

            var best: View? = null
            var bestScore = 0f
            for (v in views) {
                if (v.visibility == View.GONE || v.width <= 0 || v.height <= 0) continue
                runCatching { v.getLocationOnScreen(loc) }
                val score = RectMatch.iou(
                    RectMatch.of(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height), box
                )
                if (score > bestScore) { bestScore = score; best = v }
            }
            // 0.55 = 跟框基本重合：tab 容器≈1.0，图标≈0.1，整条底栏≈0.2
            val hit = best
            if (hit != null && bestScore >= 0.55f) {
                rectHits[target] = hit
                if (hiddenTargets.add("$currentPkg:probe:$target"))
                    Log.i(
                        "Wurao",
                        "rect 命中 view=${hit.javaClass.name} size=${hit.width}x${hit.height} " +
                            "iou=$bestScore target=$target",
                    )
                forceGone(hit, target)
            }
        }
    }

    private fun forceGone(v: View, target: String) {
        if (v.visibility != View.GONE) v.visibility = View.GONE
        v.isClickable = false
        v.isEnabled = false
        v.layoutParams?.let { lp ->
            if (lp.width != 0 || lp.height != 0) {
                runCatching { lp.width = 0; lp.height = 0; v.layoutParams = lp }
            }
        }
        if (hiddenTargets.add("$currentPkg:$target")) {
            Log.i("Wurao", "hid $target in $currentPkg (rect)")
            reportHit(currentPkg, target, Act.HIDE)
        }
    }

    private val attrRegex = Regex("\\[(text|desc|vid|name)\\*?=\"([^\"]+)\"\\]")
    private val rectRegex = Regex("\\[rect=\"([^\"]+)\"\\]")
    private val genericViews = setOf(
        "View", "ViewGroup", "FrameLayout", "LinearLayout", "RelativeLayout",
        "ConstraintLayout", "ComposeView", "AndroidComposeView", "SurfaceView",
        "TextureView", "DecorView", "CoordinatorLayout",
    )

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
        // 位置选择器：自绘界面唯一能定位的方式，优先于属性匹配
        rectRegex.find(selfPart)?.groupValues?.get(1)?.let { return rectHits(it, v) }
        val attrs = attrRegex.findAll(selfPart).toList()
        if (attrs.isEmpty()) return false
        // 纯通用容器类名会命中满屏同类 View，旧规则直接跳过（避免误伤整片界面）
        if (attrs.size == 1 && attrs[0].groupValues[1] == "name" &&
            attrs[0].groupValues[2].substringAfterLast('.') in genericViews
        ) return false
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
                // 无障碍节点树的层级和 View 树不一定一致（容器 vid 常对不上）。
                // 找不到时降级：仍按右侧自身属性命中 —— 否则带 << 的规则几乎总不生效。
                if (seenParent && !found && containerMissLogged.add(target))
                    Log.i("Wurao", "container miss, fallback: $target")
            }
        }
        return true
    }

    /** 按抓屏时的屏幕区域匹配：中心点落在框内，且尺寸不能比框大太多（防命中父容器）。 */
    private fun rectHits(raw: String, v: View): Boolean {
        val p = raw.split(",").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        val loc = IntArray(2)
        runCatching { v.getLocationOnScreen(loc) }
        val vl = loc[0]; val vt = loc[1]
        val vr = vl + v.width; val vb = vt + v.height
        val tw = p[2] - p[0]; val th = p[3] - p[1]
        if (tw <= 0 || th <= 0 || vr <= vl || vb <= vt) return false
        val cx = (vl + vr) / 2; val cy = (vt + vb) / 2
        val inside = cx >= p[0] - 8 && cx <= p[2] + 8 && cy >= p[1] - 8 && cy <= p[3] + 8
        val fit = (vr - vl) <= tw * 1.5f && (vb - vt) <= th * 1.5f
        return inside && fit
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
                "name" -> cls == value || v.javaClass.name == value
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

    /** 兼容 AdClose(`domain, x`)、AdGuard(`||x^`)、hosts(`0.0.0.0 x`)、纯域名。 */
    private fun cleanDomain(line: String): String? {
        var d = line.trim().lowercase()
        if (d.isEmpty() || d.startsWith("#") || d.startsWith("!")) return null
        // AdClose 域名表格式：domain, xxx.com（旧落位文件里可能还带着前缀）
        if (d.startsWith("domain,")) d = d.removePrefix("domain,").trim()
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
