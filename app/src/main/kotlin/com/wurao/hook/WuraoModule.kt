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
        val rules = RuleStore.all().filter {
            it.enabled && it.act == Act.HIDE && (it.scope as? com.wurao.core.model.Scope.App)?.pkg == pkg
        }
        if (rules.isEmpty()) return
        currentPkg = pkg
        hideRecursive(vg, rules)
    }

    private fun hideRecursive(v: View, rules: List<Rule>) {
        for (r in rules) {
            if (matches(r, v)) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    Log.i("Wurao", "hid ${r.target} in $currentPkg")
                }
                return
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) hideRecursive(v.getChildAt(i), rules)
        }
    }

    private var currentPkg = ""

    /** 规则匹配 View：解析选择器字符串，提取 text/vid 匹配这个 View。
     *  简化版：完整的选择器引擎（SelectorMatcher + View 适配器）后面再接。 */
    private fun matches(r: Rule, v: View): Boolean {
        val t = r.target
        // [text*="跳过"] → 匹配 text 包含
        Regex("\\[text\\*?=\"([^\"]+)\"\\]").find(t)?.let { m ->
            val txt = when (v) {
                is android.widget.TextView -> v.text?.toString() ?: ""
                else -> v.contentDescription?.toString() ?: ""
            }
            if (!txt.contains(m.groupValues[1])) return false
        }
        // [vid="ad_container"] → 匹配资源名
        Regex("\\[vid=\"([^\"]+)\"\\]").find(t)?.let { m ->
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: return@let
            if (name != m.groupValues[1]) return false
        }
        // [desc*="广告"] → 匹配 contentDescription
        Regex("\\[desc\\*?=\"([^\"]+)\"\\]").find(t)?.let { m ->
            val cd = v.contentDescription?.toString() ?: ""
            if (!cd.contains(m.groupValues[1])) return false
        }
        return true
    }
}
