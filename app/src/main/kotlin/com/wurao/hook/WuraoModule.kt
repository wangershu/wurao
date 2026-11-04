package com.wurao.hook

import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed hook 入口（libxposed api 102 的新 API）。
 *
 * 「藏起来」在这里做：hook ViewGroup.dispatchDraw → 遍历子 View → 匹配 HIDE 规则的控件
 * 直接 view.visibility = GONE —— 真消失，不是盖住。
 * 规则通过无扰 App 的 ContentProvider 跨进程读（不是读文件，Scoped Storage 读不到）。
 * 「点掉」是无障碍那边做的（dispatchGesture 点坐标），不在这。
 */
class WuraoModule : XposedModule() {

    @Volatile private var cachedRules = emptyList<String>()
    @Volatile private var lastLoadMs = 0L
    @Volatile private var interceptLogged = false
    @Volatile private var loadLogged = false
    @Volatile private var diagLogged = false
    private var currentPkg = ""
    private val hiddenTargets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        hook(ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java))
            .intercept { chain ->
                val vg = chain.thisObject as? ViewGroup
                if (vg != null) hideMatched(vg, pkg)
                chain.proceed()
            }
        Log.i("Wurao", "hooked $pkg")
    }

    private fun hideMatched(vg: ViewGroup, pkg: String) {
        val rules = loadHideRules(pkg)
        if (rules.isEmpty()) return
        currentPkg = pkg
        hideRecursive(vg, rules)
    }

    /**
     * 读本应用的规则（框架托管的 RemotePreferences，按应用切分）。
     * 一次性落位、按包读一份，不 IPC、不需要无扰在后台。
     * 2 秒节流：规则刚落位后切回来能生效，又不会每帧读。
     */
    private fun loadHideRules(pkg: String): List<String> {
        val cachedBefore = cachedRules.size
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLoadMs < 2000) return cachedRules
        lastLoadMs = now
        val list = runCatching {
            val prefs = getRemotePreferences("wurao_rules_$pkg")
                ?: return@runCatching emptyList()
            val raw = prefs.getString("rules", "") ?: ""
                raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching {
                    String(java.util.Base64.getDecoder().decode(line), Charsets.UTF_8)
                }.getOrNull()
            }
        }.getOrElse { e ->
            Log.w("Wurao", "remote prefs failed: ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
        cachedRules = list
        if (list.size != cachedBefore) Log.i("Wurao", "rules loaded: ${list.size} for $pkg")
        return cachedRules
    }

    private fun hideRecursive(v: View, rules: List<String>) {
        for (target in rules) {
            if (matchesTarget(target, v)) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    // 尺寸也归零，避免某些容器给 GONE 的 View 仍留占位
                    v.layoutParams?.let { lp ->
                        runCatching { lp.width = 0; lp.height = 0; v.layoutParams = lp }
                    }
                    if (hiddenTargets.add("$currentPkg:$target"))
                        Log.i("Wurao", "hid $target in $currentPkg")
                }
                return
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) hideRecursive(v.getChildAt(i), rules)
        }
    }

    /**
     * 选择器匹配：RuleGen 生成的是降级链（如 `[vid="a"] << [text="b"]`），
     * 把里面所有 [属性="值"] 提取出来，任一命中即视为匹配（先让效果可靠出现）。
     */
    private fun matchesTarget(target: String, v: View): Boolean {
        val attrs = Regex("\\[(text|desc|vid|name)\\*?=\"([^\"]+)\"\\]").findAll(target).toList()
        if (attrs.isEmpty()) return false
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
}
