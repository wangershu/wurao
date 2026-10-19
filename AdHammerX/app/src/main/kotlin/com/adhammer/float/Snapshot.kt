package com.adhammer.float

/**
 * 布局快照：把无障碍服务拿到的 View 树转成文本，供规则生成与 AI 分析使用。
 *
 * 输出到文本的每一行是一个 View：类名 / 资源 id / 文本 / 是否可点击，
 * 与原版「悬浮窗 → 快照 → 查看布局」的呈现一致。
 */
object Snapshot {

    @Volatile
    private var last: String = ""

    /** 当前页面（包名/Activity），由无障碍服务更新 */
    @Volatile
    private var page: String = "等待页面信息…"

    fun setPage(pkg: String?, act: String?) {
        page = buildString {
            append(act ?: "unknown")
            if (!pkg.isNullOrBlank()) append("\n$pkg")
        }
    }

    fun currentPage(): String = page

    /** 抓取当前窗口的 View 树 */
    fun capture(): String? {
        val service = BaJieService.instance ?: return null
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val sb = StringBuilder()
        sb.append("# page: ").append(page).append('\n')
        runCatching { dump(root, sb, 0) }
        last = sb.toString()
        return last
    }

    private fun dump(node: android.view.accessibility.AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 24) return
        val cls = node.className?.toString() ?: "?"
        val id = node.viewIdResourceName ?: ""
        val text = node.text?.toString()?.take(40) ?: ""
        val clickable = if (node.isClickable) " [clickable]" else ""
        sb.append("  ".repeat(depth))
            .append(cls.substringAfterLast('.'))
            .append(if (id.isNotEmpty()) " id=$id" else "")
            .append(if (text.isNotEmpty()) " txt=\"$text\"" else "")
            .append(clickable)
            .append('\n')
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            dump(child, sb, depth + 1)
        }
    }

    fun last(): String = last
}
