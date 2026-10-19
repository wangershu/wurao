package com.adhammer.hook

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.adhammer.rules.StatsStore
import com.adhammer.rules.model.ViewRule
import com.adhammer.util.HLog

/**
 * 精准 View 匹配与隐藏引擎。
 *
 * 解决「跳过按钮既没文字也没 id」的场景：通过
 * 类名前缀 + 资源名 + 文本 + 父层级 + 子层级 + 子序号 定位到最底层 View 后隐藏它。
 *
 * 匹配流程：
 *  1. 入口命中：类名前缀 v（startsWith）且开关 s
 *  2. 入口校验：id（资源名）+ t（文本）
 *  3. 定位目标：pl 向上找父 / cl 向下找子 / sq 指定子序号
 *  4. 父、子再用 p/pId/pt、c/cId/ct 二次校验
 *  5. 递归隐藏整棵子树
 */
class ViewEngine(
    private val viewMap: Map<String, List<ViewRule>>,
    private val pkg: String
) {
    /** 当前 Activity 名（由外部在 onCreate 时更新） */
    @Volatile
    var actName: String = ""

    /** 检查某个 View 是否需要隐藏。 */
    fun check(view: View) {
        if (!view.isAttachedToWindowCompat()) return
        val name = view.javaClass.name
        val rule = matchEntry(name) ?: matchEntry(normalizeClassName(view)) ?: return
        if (!checkCondition(rule.id, rule.t, view)) return
        HLog.d("match view rule: ${rule.v}")
        HitLog.hit(pkg, rule.id ?: rule.t ?: rule.v ?: "", "View屏蔽", StatsStore.KIND_VIEW)
        resolveAndHide(rule, view)
    }

    /** 从根 View 递归扫描（onResume 时用，覆盖静态布局）。 */
    fun scanTree(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) scanTree(root.getChildAt(i))
        }
        runCatching { check(root) }
    }

    // ---------- 入口匹配 ----------

    private fun matchEntry(className: String): ViewRule? {
        if (className.isBlank()) return null
        // "*" = 全局规则（分析器/手工规则不知道具体 Activity 时用）
        for (key in listOf(actName, pkg, "*")) {
            val list = viewMap[key] ?: continue
            for (r in list) {
                if (r.s && !r.v.isNullOrBlank() && className.startsWith(r.v)) return r
            }
        }
        return null
    }

    // ---------- 目标定位与隐藏 ----------

    fun resolveAndHide(rule: ViewRule, view: View) {
        when {
            rule.isHookType1() -> {
                // ht=1：先校验父链
                var ok = true
                if (rule.pl > 0) {
                    val parent = walkUp(view, rule.pl)
                    ok = parent != null && matchClass(parent, rule.p, rule.pId, rule.pt)
                }
                if (!ok) return
                // 再校验子层
                if (rule.cl > 0 && view is ViewGroup && !rule.c.isNullOrBlank()) {
                    ok = findChildStrict(rule, view, 1)
                }
                if (ok) hideBySeq(rule, view)
            }
            rule.pl > 0 -> {
                val parent = walkUp(view, rule.pl)
                if (parent != null && matchClass(parent, rule.p, rule.pId, rule.pt)) {
                    hideBySeq(rule, view)
                }
            }
            else -> {
                if (rule.cl <= 0) hideBySeq(rule, view)
                else if (view is ViewGroup && !rule.c.isNullOrBlank()) findChildLoose(rule, view, 0)
            }
        }
    }

    private fun ViewRule.isHookType1() = ht == 1

    private fun walkUp(view: View, level: Int): View? {
        var cur: View = view
        repeat(level) {
            if (cur is AbsListView) return null
            val p = cur.parent
            cur = when (p) {
                is ViewGroup -> p
                is View -> return p
                else -> return null
            }
        }
        return cur
    }

    private fun findChildStrict(rule: ViewRule, view: View, depth: Int): Boolean {
        if (rule.cl < depth) return false
        if (rule.cl == depth) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (matchClass(view.getChildAt(i), rule.c, rule.cId, rule.ct)) return true
                }
            }
            return false
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (findChildStrict(rule, view.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun findChildLoose(rule: ViewRule, view: View, depth: Int): Boolean {
        if (depth > rule.cl) return false
        if (depth == rule.cl && matchClass(view, rule.c, rule.cId, rule.ct)) {
            hideBySeq(rule, view)
            return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (findChildLoose(rule, view.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun hideBySeq(rule: ViewRule, view: View) {
        if (rule.sq.isNullOrBlank() || view !is ViewGroup) {
            hideTree(view)
            return
        }
        val group = view as ViewGroup
        val count = group.childCount
        for (seq in rule.sq.split(";")) {
            val idx = seq.trim().toIntOrNull() ?: continue
            if (idx in 0 until count) hideTree(group.getChildAt(idx))
        }
    }

    private fun hideTree(view: View?) {
        if (view == null) return
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) hideTree(view.getChildAt(i))
        }
        hide(view)
    }

    /** 五重保险：宽高归零 + 边距归零 + GONE + padding 归零 + 全透明 */
    private fun hide(view: View) {
        runCatching {
            val lp = view.layoutParams
            if (lp != null && (lp.height > 0 || lp.width > 0)) {
                lp.height = 0
                lp.width = 0
                if (lp is ViewGroup.MarginLayoutParams) lp.setMargins(0, 0, 0, 0)
                view.layoutParams = lp
            }
            view.visibility = View.GONE
            view.setPadding(0, 0, 0, 0)
            view.alpha = 0f
        }.onFailure { HLog.w("hide view failed: ${it.message}") }
    }

    // ---------- 条件校验 ----------

    private fun matchClass(view: View?, className: String?, idCond: String?, textCond: String?): Boolean {
        if (className.isNullOrBlank()) return true
        if (view == null) return false
        if (view.javaClass.name.contains(className)) return checkCondition(idCond, textCond, view)
        val family = normalizeClassName(view)
        return family.contains(className) && checkCondition(idCond, textCond, view)
    }

    private fun checkCondition(idCond: String?, textCond: String?, view: View): Boolean {
        if (!idCond.isNullOrBlank()) {
            val resName = resNameOf(view)
            if (resName.isBlank() || !matchResName(resName, idCond)) return false
        }
        if (textCond.isNullOrBlank()) return true
        return matchText(textOf(view), textCond)
    }

    /** 文本匹配：#xx# 全等，; 分隔多候选，默认包含 */
    private fun matchText(viewText: String, cond: String): Boolean {
        if (viewText.isBlank()) return cond.contains("##")
        if (cond.contains(";")) {
            return cond.split(";").any { matchSingle(viewText, it) }
        }
        return matchSingle(viewText, cond)
    }

    private fun matchSingle(viewText: String, one: String): Boolean =
        if (one.contains("#") && one.length > 1) "#$viewText#".equals(one, true)
        else viewText.contains(one)

    private fun matchResName(resName: String, cond: String): Boolean =
        if (cond.contains(";")) cond.split(";").any { resName.equals(it, true) }
        else resName.equals(cond, true)

    private fun resNameOf(view: View): String = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")

    private fun textOf(view: View): String = runCatching {
        (view as? TextView)?.text?.toString()?.trim() ?: ""
    }.getOrDefault("")

    /** 归一化类名：把常见控件收敛到可比对的族名 */
    private fun normalizeClassName(view: View): String = when {
        view is WebView -> WebView::class.java.name
        view is LinearLayout -> LinearLayout::class.java.name
        view is RelativeLayout -> RelativeLayout::class.java.name
        view is FrameLayout -> FrameLayout::class.java.name
        view is ImageButton -> ImageButton::class.java.name
        view is ImageView -> ImageView::class.java.name
        view is Button -> Button::class.java.name
        view is TextView -> TextView::class.java.name
        else -> view.javaClass.superclass?.name ?: ""
    }

    private fun View.isAttachedToWindowCompat(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 19) isAttachedToWindow else true
    }.getOrDefault(true)
}
