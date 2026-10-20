package com.adhammer.float

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.adhammer.rules.Jsonx

/**
 * 布局快照：把无障碍服务拿到的 View 树同时产出两份东西 ——
 *
 * 1. **结构化节点** [Node]：给规则工厂勾选、生成 View 规则 / 八戒步骤用
 * 2. **文本**：给 AI 分析用（也是原版「快照 → 查看布局」的那个样子）
 *
 * 原版靠 Xposed 在目标进程里反射 View 树，这里改用无障碍的
 * [AccessibilityNodeInfo]，不需要 hook 也能拿到类名 / 资源 id / 文本 / 坐标。
 * 代价是拿不到自绘 View 的内部属性 —— 但对「找跳过按钮」这件事足够了。
 */
object Snapshot {

    /**
     * 一个 View 节点。
     *
     * [cls] 是全限定类名（生成规则要用全名），[id] 已经去掉 `包名:id/` 前缀。
     * [bounds] 是屏幕坐标 l,t,r,b —— 点屏幕取坐标时用它把点换算成「点的是哪个控件」。
     */
    data class Node(
        val depth: Int = 0,
        val cls: String = "",
        val id: String? = null,
        val text: String? = null,
        val desc: String? = null,
        val clickable: Boolean = false,
        val bounds: List<Int> = emptyList()
    ) {
        val short: String get() = cls.substringAfterLast('.')

        /** 列表里显示什么：文本 → 描述 → 资源名 → 类名 */
        val label: String
            get() = text?.takeIf { it.isNotBlank() }
                ?: desc?.takeIf { it.isNotBlank() }
                ?: id?.takeIf { it.isNotBlank() }
                ?: short
    }

    /** 一次快照的完整结果 */
    data class Capture(
        val act: String? = null,
        val pkg: String? = null,
        val text: String = "",
        val nodes: List<Node> = emptyList()
    ) {
        val actShort: String get() = act?.substringAfterLast('.') ?: "未知页面"
    }

    /**
     * 当前页面（悬浮窗面板实时显示）。
     *
     * 必须是 Compose state：面板在 setContent 时只组合一次，
     * 普通 var 改了界面不会重画，看起来就是「悬浮窗一直是等待页面信息」。
     */
    var page by mutableStateOf("等待页面信息…")
        private set

    @Volatile
    private var curAct: String = ""

    @Volatile
    private var curPkg: String = ""

    @Volatile
    private var lastText: String = ""

    /**
     * 最近一次快照（悬浮窗抓完，规则工厂直接读这个）。
     *
     * 同理必须是 state —— 之前是 @Volatile var，抓完值对了但界面不重组，
     * 表现就是「点了快照却看不到任何东西」。
     */
    var pending: Capture? by mutableStateOf(null)
        private set

    /** 最近一次「点屏幕取坐标」的结果 (x, y) */
    var picked: List<Int>? by mutableStateOf(null)

    /** 取点时所在的包名，用来判断要不要提示「目标应用变了」 */
    var pickedPkg: String? by mutableStateOf(null)

    private fun setPageInternal(pkg: String?, act: String?) {
        curPkg = pkg.orEmpty()
        curAct = act.orEmpty()
        page = buildString {
            append(act?.takeIf { it.isNotBlank() } ?: "unknown")
            if (!pkg.isNullOrBlank()) append("\n").append(pkg)
        }
    }

    fun setPage(pkg: String?, act: String?) = setPageInternal(pkg, act)

    fun currentPage(): String = page

    fun act(): String = curAct

    fun pkg(): String = curPkg

    fun last(): String = lastText

    /**
     * 抓取当前窗口。
     *
     * 返回 null 只有一种原因：无障碍服务没连上（没开或被杀）。
     * 调用方要据此给出「去开启无障碍」的明确提示，而不是沉默。
     */
    fun capture(): Capture? {
        val svc = BaJieService.instance ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null

        val nodes = ArrayList<Node>(256)
        runCatching { walk(root, nodes, 0) }
        if (nodes.isEmpty()) return null

        // 无障碍拿到的根节点不带包名时，退回无障碍事件里记下的那个
        val pkgName = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: curPkg
        val text = render(nodes)
        val cap = Capture(curAct, pkgName, text, nodes)
        pending = cap
        lastText = text
        return cap
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<Node>,
        depth: Int
    ) {
        if (depth > 30 || out.size >= 900) return
        val rect = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(rect) }
        out.add(
            Node(
                depth = depth,
                cls = node.className?.toString().orEmpty(),
                id = node.viewIdResourceName?.substringAfter("id/"),
                text = node.text?.toString()?.take(80)?.takeIf { it.isNotBlank() },
                desc = node.contentDescription?.toString()?.take(80)?.takeIf { it.isNotBlank() },
                clickable = node.isClickable,
                bounds = listOf(rect.left, rect.top, rect.right, rect.bottom)
            )
        )
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(child, out, depth + 1)
        }
    }

    /** 结构化节点 → 文本（保持老格式：`# page:` + 包名 + 缩进树） */
    private fun render(nodes: List<Node>): String = buildString {
        append("# page: ").append(curAct.ifBlank { "unknown" }).append('\n')
        append(curPkg).append('\n')
        nodes.forEach { n ->
            append("  ".repeat(n.depth))
            append(n.cls.ifBlank { "View" })
            n.id?.let { append(" id=").append(it) }
            n.text?.let { append(" txt=\"").append(it).append('"') }
            n.desc?.let { append(" desc=\"").append(it).append('"') }
            if (n.bounds.size == 4) {
                append(" b=").append(n.bounds.joinToString(","))
            }
            if (n.clickable) append(" [clickable]")
            append('\n')
        }
    }

    /** 记一次坐标拾取 */
    fun pick(x: Int, y: Int) {
        picked = listOf(x, y)
        pickedPkg = curPkg
    }

    fun clearPick() {
        picked = null
        pickedPkg = null
    }

    fun clearPending() {
        pending = null
    }

    /** 快照转 JSON（走 Intent 传或存盘都用它） */
    fun toJson(cap: Capture): String = Jsonx.toJson(cap) ?: ""

    fun fromJson(json: String?): Capture? {
        if (json.isNullOrBlank()) return null
        return runCatching { Jsonx.gson.fromJson(json, Capture::class.java) }
            .getOrNull()
            ?.takeIf { it.nodes.isNotEmpty() }
    }
}
