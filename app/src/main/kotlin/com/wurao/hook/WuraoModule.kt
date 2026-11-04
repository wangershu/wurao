package com.wurao.hook

import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.wurao.core.model.Act
import com.wurao.core.model.Rule
import com.wurao.core.store.RuleStore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed hook 入口（libxposed api 102 的新 API）。
 *
 * 「藏起来」在这里做：hook ViewGroup.dispatchDraw → 遍历子 View → 匹配 HIDE 规则的控件
 * 直接 view.visibility = GONE —— 真消失，不是盖住。
 * 「点掉」是无障碍那边做的（dispatchGesture 点坐标），不在这。
 */
class WuraoModule : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        // hook ViewGroup.dispatchDraw：每次绘制时遍历子 View，匹配 HIDE 规则就 GONE
        hook(ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java))
            .intercept { chain ->
                val vg = chain.thisObject as? ViewGroup
                if (vg != null) hideMatched(vg, pkg)
                chain.proceed()
            }
        Log.i("Wurao", "hooked $pkg")
    }

    /** 遍历 View 树，匹配 HIDE 规则的控件直接 GONE。 */
    private fun hideMatched(vg: ViewGroup, pkg: String) {
        // hook 进程读不到我的 App 的内存（RuleStore 是内存的）→ 读磁盘上的规则文件
        val rules = loadHideRules(pkg)
        if (rules.isEmpty()) return
        currentPkg = pkg
        hideRecursive(vg, rules)
    }

    /** 读磁盘上的规则文件（我的 App 写到的共享存储），过滤 HIDE + 这个包。 */
    private fun loadHideRules(pkg: String): List<String> {
        val f = java.io.File("/sdcard/Android/data/com.wurao.app/files/rules.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val parts = line.split("|")
                // id|domain|target|act|enabled|pkg
                if (parts.size < 6) return@mapNotNull null
                if (parts[3] != "hide" || parts[4] != "true" || parts[5] != pkg) return@mapNotNull null
                parts[2]  // target（选择器字符串）
            }
        }.getOrDefault(emptyList())
    }

    private fun hideRecursive(v: View, rules: List<String>) {
        for (target in rules) {
            if (matchesTarget(target, v)) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    Log.i("Wurao", "hid $target in $currentPkg")
                }
                return
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) hideRecursive(v.getChildAt(i), rules)
        }
    }

    private var currentPkg = ""

    /** 选择器字符串匹配 View：解析 [text*="..."][vid="..."][desc*="..."] 是否命中这个 View。 */
    private fun matchesTarget(target: String, v: View): Boolean {
        // [text*="跳过"] → 匹配 text 包含
        Regex("\\[text\\*?=\"([^\"]+)\"\\]").find(target)?.let { m ->
            val txt = when (v) {
                is android.widget.TextView -> v.text?.toString() ?: ""
                else -> v.contentDescription?.toString() ?: ""
            }
            if (!txt.contains(m.groupValues[1])) return false
        }
        // [vid="ad_container"] → 匹配资源名
        Regex("\\[vid=\"([^\"]+)\"\\]").find(target)?.let { m ->
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: return@let
            if (name != m.groupValues[1]) return false
        }
        // [desc*="广告"] → 匹配 contentDescription
        Regex("\\[desc\\*?=\"([^\"]+)\"\\]").find(target)?.let { m ->
            val cd = v.contentDescription?.toString() ?: ""
            if (!cd.contains(m.groupValues[1])) return false
        }
        return true
    }
}
