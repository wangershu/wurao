package com.wurao.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

import android.accessibilityservice.AccessibilityService
import androidx.core.app.NotificationCompat
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
        // 测试期间保活：前台服务 + 常驻通知，防止系统把服务回收掉
        // （服务被回收的表现：框出不来、抓取没反应）
        startKeepaliveNotification()
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
        val n = lastSnapshot?.size ?: 0
        android.util.Log.i("Wurao", "抓到 $n 个控件 ($lastPkg)")
        // 抓到就把框画在当前界面上：这时你正看着目标 App，直接点选最自然，
        // 不用再切回我们的界面去点按钮
        val shot = lastSnapshot
        if (!shot.isNullOrEmpty()) {
            FrameOverlay.show(this, shot) { pick, act ->
                val rules = RuleGen.rules(pick.pkg ?: "", pick.node, pick.container, act = act)
                val store = com.wurao.core.store.RuleStore
                // 重抓时清掉同应用里旧的纯通用容器名规则（它们无法定位）
                com.wurao.core.store.RuleStore.all().filter {
                    com.wurao.ui.ruleScopePkg(it) == (pick.pkg ?: "") &&
                        com.wurao.ui.isGenericNameSelector(it.target)
                }.forEach { com.wurao.core.store.RuleStore.remove(it.id) }
                rules.forEach { store.upsert(it) }
                android.util.Log.i("Wurao", "抓屏保存 pkg=${pick.pkg} act=${act.key} 共 ${rules.size} 条 target=${rules.firstOrNull()?.target}")
                com.wurao.ui.Ui.pushRulesAsync(this)
                android.widget.Toast.makeText(
                    this,
                    "已保存 ${rules.size} 条：${rules.firstOrNull()?.target}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                FrameOverlay.hide(this)
            }
        }
    }

    override fun onInterrupt() {}

    /** 遍历当前窗口。 */
    fun captureNow(): List<Pick> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pick>()
        // 用当前窗口自己的包名，不用 lastPkg：悬浮球点击的无障碍事件
        // 可能把 lastPkg 写成无扰自己，规则就会落到错误的包
        val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: lastPkg
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

    private fun infoOf(n: AccessibilityNodeInfo): RuleGen.NodeInfo {
        val r = android.graphics.Rect()
        runCatching { n.getBoundsInScreen(r) }
        return RuleGen.NodeInfo(
            className = n.className?.toString() ?: "",
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            vid = n.viewIdResourceName?.substringAfterLast('/'),
            clickable = n.isClickable,
            rect = "${r.left},${r.top},${r.right},${r.bottom}",
        )
    }

    /** 前台服务 + 常驻通知：防系统回收（测试期间）。 */
    private fun startKeepaliveNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel("svc", "抓屏服务", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val n = NotificationCompat.Builder(this, "svc")
                .setSmallIcon(android.R.drawable.ic_notification_overlay)
                .setContentTitle("无扰正在运行")
                .setContentText("抓屏 / 自动化的无障碍服务")
                .setOngoing(true)
                .build()
            startForeground(1, n)
            android.util.Log.i("Wurao", "保活通知已挂")
        }.onFailure { android.util.Log.w("Wurao", "保活通知挂了（不影响功能）", it) }
    }
}
