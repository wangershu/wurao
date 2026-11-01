package com.wurao.inapp

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import com.wurao.core.selector.NodeAdapter

/**
 * 选择器引擎的 **View 版适配器**：让 core/selector 那一套能直接跑在目标进程里。
 *
 * 这里的每一条语义映射都是坑换来的（无障碍的 AccessibilityNodeInfo 语义 ≠ View 语义，
 * 照抄会让订阅规则静默失效），改任何一条都要写清理由：
 *
 * | 选择器属性 | View 侧怎么取 | 为什么这么定 |
 * |---|---|---|
 * | `id` | `pkg:id/短名`（全限定） | GKD 订阅里 `id` 是全限定，`vid` 才是短名，混用会让用 id 的规则全废 |
 * | `vid` | 资源短名 | 同上 |
 * | `text` | 只取 TextView 的 text | 不把 contentDescription 混进来，否则"按文本找"和"按描述找"变成一回事 |
 * | `visibleToUser` | `VISIBLE && 宽>0 && 高>0` | **不查祖先链**：一来贵，二来广告容器常是"父已 GONE 但子还在树上"，查了反而漏 |
 * | `childCount` | ViewGroup 的子数 | View 侧含全部子节点（无障碍侧会过滤不可见节点），所以通常更大 |
 * | `depth` | 往上数，上限 64 | 防异常树 |
 */
class ViewAdapter(private val pkg: String) : NodeAdapter<View> {

    /** 数值 id → 短名。同一进程内可缓存，`getResourceEntryName` 不便宜。 */
    private val resCache = HashMap<Int, String?>()

    override fun attr(node: View, path: List<String>): Any? {
        val head = path.firstOrNull() ?: return null
        val v: Any? = when (head) {
            "text" -> textOf(node)
            "desc" -> runCatching { node.contentDescription?.toString() }.getOrNull()
            "id" -> shortId(node)?.let { "$pkg:id/$it" }
            "vid" -> shortId(node)
            "name" -> node.javaClass.name
            "childCount" -> childCount(node)
            "clickable" -> node.isClickable
            "longClickable" -> node.isLongClickable
            "focusable" -> node.isFocusable
            "checkable" -> node is Checkable
            "checked" -> (node as? Checkable)?.isChecked
            "enabled" -> node.isEnabled
            "visible", "visibleToUser" -> isVisible(node)
            "index" -> indexOf(node)
            "depth" -> depthOf(node)
            "left" -> bounds(node).left
            "top" -> bounds(node).top
            "right" -> bounds(node).right
            "bottom" -> bounds(node).bottom
            "width" -> node.width
            "height" -> node.height
            else -> null
        }
        if (path.size == 1) return v
        // 二级派生：目前只有 length（挡掉「跳过广告可获赠会员」这类长文案按钮就靠它）
        return when (path[1]) {
            "length" -> (v as? String)?.length
            else -> null
        }
    }

    override fun className(node: View): String? = node.javaClass.name

    override fun childCount(node: View): Int = (node as? ViewGroup)?.childCount ?: 0

    override fun child(node: View, index: Int): View? =
        runCatching { (node as? ViewGroup)?.getChildAt(index) }.getOrNull()

    override fun parent(node: View): View? = node.parent as? View

    override fun key(node: View): Any = System.identityHashCode(node)

    // ---------------- 内部 ----------------

    private fun textOf(v: View): String? = runCatching {
        (v as? TextView)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun shortId(v: View): String? {
        val id = v.id
        if (id == View.NO_ID) return null
        resCache[id]?.let { return it }
        val name = runCatching { v.resources.getResourceEntryName(id) }.getOrNull()
        resCache[id] = name
        return name
    }

    private fun isVisible(v: View): Boolean =
        v.visibility == View.VISIBLE && v.width > 0 && v.height > 0

    private fun indexOf(v: View): Int = runCatching {
        val p = v.parent as? ViewGroup ?: return 0
        for (i in 0 until p.childCount) if (p.getChildAt(i) === v) return i
        0
    }.getOrDefault(0)

    private fun depthOf(v: View): Int = runCatching {
        var d = 0
        var cur: View? = v.parent as? View
        while (cur != null && d < 64) {
            d++
            cur = cur.parent as? View
        }
        d
    }.getOrDefault(0)

    private val rect = Rect()

    /** 屏幕绝对坐标。每次复用同一个 Rect，避免热路径上反复分配。 */
    private fun bounds(v: View): Rect = runCatching {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        rect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        rect
    }.getOrDefault(rect)

    /**
     * 补充手段：做过资源混淆的 App 取不到资源名（[shortId] 返回 null），
     * 但常能把规则里的名字反查成数值 id —— 这时只能直接比 `view.id`。
     *
     * 刻意不塞进 core/selector：它需要目标应用的 Resources，是执行器私有的本事，
     * 放进纯判定层会把 Android 依赖带进 core。
     */
    fun numericId(v: View, name: String): Int {
        val key = name.substringAfterLast('/').substringAfterLast(':')
        if (key.isBlank()) return 0
        return runCatching { v.resources.getIdentifier(key, "id", pkg) }.getOrDefault(0)
    }
}
