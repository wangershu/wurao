package com.wurao.assist

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wurao.core.analyze.RuleGen

/**
 * 抓屏服务（**检视层**）：把当前界面的控件摊成一张清单，你在里面点一个，就生成规则。
 *
 * 为什么必须依赖无障碍：Xposed 只能碰自己所在的进程，没法在**任意 App** 上读界面。
 * 这是系统划的安全边界，绕不过去（架构文档里定过）。
 *
 * 两点刻意的设计：
 * 1. **不做常驻监听**：只在"抓一屏"模式下才处理事件，抓完立刻关掉 ——
 *    否则每次界面变化都遍历整棵树，那本身就是耗电大户；
 * 2. 本服务跑在**模块自己的进程**，抓到的东西直接在内存里给界面用，不需要跨进程通道。
 */
class SnapshotService : AccessibilityService() {

    /** 抓到的一个控件：它自己 + 它的容器（生成规则时要靠容器把范围锁住）。 */
    data class Pick(
        val node: RuleGen.NodeInfo,
        val container: RuleGen.NodeInfo?,
        val pkg: String?,
        /** 控件在屏幕上的位置，画框用。 */
        val bounds: android.graphics.Rect,
    )

    companion object {
        @Volatile
        var instance: SnapshotService? = null
            private set

        /** 是否开着：界面上要知道"服务没开"并提示去系统设置里开。 */
        val active: Boolean get() = instance != null

        /** 置 true 后切到目标 App，服务抓一张就自动关掉。 */
        @Volatile
        var wantCapture: Boolean = false

        /**
         * 只抓这个包名的窗口。
         * 不指定的话，第一个到达的窗口事件常常是状态栏（com.android.systemui），
         * 结果抓回来的根本不是你要的那个 App —— 实测就是这么抓错的。
         */
        @Volatile
        var wantPkg: String? = null

        /** 最近抓到的那屏。 */
        @Volatile
        var lastSnapshot: List<Pick>? = null

        @Volatile
        var lastPkg: String? = null

        /** 节点上限：整棵树可能上千个，摊成清单没必要也不该那么长。 */
        private const val MAX_NODES = 300
    }

    override fun onServiceConnected() {
        instance = this
        android.util.Log.i("Wurao", "抓屏服务已连接")
    }

    override fun onDestroy() {
        instance = null
        android.util.Log.i("Wurao", "抓屏服务已断开")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 没开抓屏模式就什么都不做 —— 常驻遍历是要付出电量的
        if (!wantCapture) return
        val t = event?.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event?.packageName?.toString()
        // 不是目标 App 就不消费标记，继续等它出现
        if (wantPkg != null && pkg != wantPkg) return
        wantCapture = false
        wantPkg = null
        lastPkg = pkg
        lastSnapshot = captureNow()
        android.util.Log.i("Wurao", "抓到 ${lastSnapshot?.size ?: 0} 个控件 ($lastPkg)")
    }

    override fun onInterrupt() {}

    /** 遍历当前窗口。 */
    private fun captureNow(): List<Pick> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pick>()
        val pkg = lastPkg
        try {
            walk(root, null, out, pkg)
        } finally {
            runCatching { root.recycle() }
        }
        return out
    }

    private fun walk(
        n: AccessibilityNodeInfo,
        parent: AccessibilityNodeInfo?,
        out: ArrayList<Pick>,
        pkg: String?,
    ) {
        if (out.size >= MAX_NODES) return
        // 只收用户看得见的：不可见的控件抓来也没用
        if (n.isVisibleToUser) {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            out.add(Pick(infoOf(n), parent?.let(::infoOf), pkg, r))
        }
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            walk(c, n, out, pkg)
        }
    }

    private fun infoOf(n: AccessibilityNodeInfo) = RuleGen.NodeInfo(
        className = n.className?.toString() ?: "",
        text = n.text?.toString(),
        desc = n.contentDescription?.toString(),
        vid = n.viewIdResourceName?.substringAfterLast('/'),
        clickable = n.isClickable,
    )
}
