package com.adhammer.float

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adhammer.rules.model.Step
import com.adhammer.rules.model.Task
import com.adhammer.util.HLog

/**
 * 八戒：无障碍自动化服务。
 *
 * 用途：悟空（Xposed）搞不定的广告 —— 比如按钮没有稳定的类名/资源名、
 * 或者需要先滑动再点击 —— 交给无障碍按步骤模拟操作。
 *
 * 任务来自订阅的 ba_jie_tasks.txt，按 Activity 匹配后顺序执行 [Step]。
 */
class BaJieService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        HLog.i("BaJieService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                val act = event.className?.toString()
                Snapshot.setPage(pkg, act)
                // 页面切换时尝试匹配任务
                val p = pkg ?: return
                if (!com.adhammer.ui.UiState.baJieOn) return
                // 应用级细分开关：这个应用单独关掉了八戒
                if (com.adhammer.ui.UiState.subOff(p, com.adhammer.rules.Cfg.SUB_BA_JIE)) return
                val tasks = com.adhammer.ui.UiState.tasksOf(p)
                if (tasks.isEmpty()) return
                val a = act ?: return
                tasks.filter { it.isEnable && (it.act == null || it.act == a) }
                    .forEach { run(it) }
            }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 按顺序执行一个任务的所有步骤 */
    private fun run(task: Task) {
        HLog.d("run task: ${task.pkg} steps=${task.steps.size}")
        com.adhammer.hook.HitLog.hit(
            task.pkg ?: "",
            task.name ?: task.act ?: "任务",
            "自动跳过",
            com.adhammer.rules.StatsStore.KIND_SKIP
        )
        var delay = 0L
        task.steps.forEach { step ->
            handler.postDelayed({ exec(step) }, delay)
            delay += step.d + step.it
        }
    }

    /** 执行单个步骤 */
    private fun exec(step: Step) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val repeat = step.c.coerceAtLeast(1)

        when {
            // 坐标点击 / 滑动
            step.x >= 0 && step.y >= 0 -> {
                val (x, y) = resolvePoint(step)
                repeat(repeat) { clickPoint(x.toFloat(), y.toFloat()) }
            }
            step.sx0 >= 0 && step.sy0 >= 0 && step.sx1 >= 0 && step.sy1 >= 0 -> {
                swipe(step.sx0.toFloat(), step.sy0.toFloat(), step.sx1.toFloat(), step.sy1.toFloat())
            }
            else -> {
                // 按 viewId / 文本 / 类名 定位
                val target = findNode(root, step)
                if (target != null) {
                    repeat(repeat) { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                } else {
                    HLog.d("step target not found: ${step.vId ?: step.t ?: step.wv}")
                }
            }
        }
    }

    private fun resolvePoint(step: Step): Pair<Int, Int> {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root != null && step.pRel && step.leftX != step.rightX) {
            // 相对坐标：按比例换算
            val w = step.rightX - step.leftX
            val h = step.bottomY - step.topY
            val dm = resources.displayMetrics
            return ((step.leftX.toFloat() / w) * dm.widthPixels).toInt() to
                    ((step.topY.toFloat() / h) * dm.heightPixels).toInt()
        }
        return step.x to step.y
    }

    private fun findNode(root: AccessibilityNodeInfo, step: Step): AccessibilityNodeInfo? {
        if (!step.vId.isNullOrBlank()) {
            val list = root.findAccessibilityNodeInfosByViewId(step.vId)
            if (!list.isNullOrEmpty()) return list[0]
        }
        if (!step.t.isNullOrBlank()) {
            val list = root.findAccessibilityNodeInfosByText(step.t)
            if (!list.isNullOrEmpty()) return list[0]
        }
        if (!step.wv.isNullOrBlank()) {
            return search(root) { it.className?.toString()?.contains(step.wv) == true && it.isClickable }
        }
        return null
    }

    private fun search(
        node: AccessibilityNodeInfo?,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = search(child, pred)
            if (hit != null) return hit
        }
        return null
    }

    private fun clickPoint(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float) {
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        val duration = if (false) 400L else 400L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    private fun repeat(times: Int, block: () -> Unit) {
        repeat(times) { block() }
    }

    companion object {
        @Volatile
        var instance: BaJieService? = null
    }
}
