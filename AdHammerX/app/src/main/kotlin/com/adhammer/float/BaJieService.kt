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

    /** 自动跳过的冷却表：pkg|文案 -> 上次点的时间，避免对同一个按钮连点 */
    private val skipCool = HashMap<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                val act = event.className?.toString()
                Snapshot.setPage(pkg, act)
                val p = pkg ?: return
                if (!com.adhammer.ui.UiState.baJieOn) return
                // 应用级细分开关：这个应用单独关掉了八戒
                if (com.adhammer.ui.UiState.subOff(p, com.adhammer.rules.Cfg.SUB_BA_JIE)) return

                // ① 显式任务（订阅里的 ba_jie_tasks）
                act?.let { a ->
                    com.adhammer.ui.UiState.tasksOf(p)
                        .filter { it.isEnable && (it.act == null || it.act == a) }
                        .forEach { run(it) }
                }

                // ② 自动跳过（不写规则也能点掉开屏广告）
                if (AutoSkip.inScope(p)) scheduleAutoSkip(p)

                // ③ 常见弹框自动关闭（升级 / 青少年模式 / 运营弹窗）
                schedulePopClose(p)
            }
        }
    }

    /**
     * 弹框比开屏广告出现得晚，而且常常是渐显动画，
     * 所以铺得更长一点：1.2s ~ 6s 之间试 4 次。
     */
    private fun schedulePopClose(pkg: String) {
        longArrayOf(1200, 2400, 4000, 6000).forEach { delay ->
            handler.postDelayed({
                if (Snapshot.pkg() != pkg) return@postDelayed
                val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@postDelayed
                val hit = PopGuard.find(root, pkg) ?: return@postDelayed
                if (PopGuard.perform(hit)) {
                    com.adhammer.hook.HitLog.hit(
                        pkg, hit.first, "关闭弹框",
                        com.adhammer.rules.StatsStore.KIND_SKIP
                    )
                }
            }, delay)
        }
    }

    /**
     * 页面刚出现时连着试几次。
     *
     * 开屏广告的跳过按钮往往是倒计时结束才出现 / 才会变成可点，
     * 只扫一次基本扫不到，所以铺一组延时重试，6 秒内还没出现就放弃。
     */
    private fun scheduleAutoSkip(pkg: String) {
        longArrayOf(400, 900, 1600, 2600, 3800, 5200).forEach { delay ->
            handler.postDelayed({
                // 页面已经切走就不点了
                if (Snapshot.pkg() != pkg) return@postDelayed
                val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@postDelayed
                val hit = AutoSkip.find(root) ?: return@postDelayed
                val key = "$pkg|${hit.text}"
                val now = android.os.SystemClock.uptimeMillis()
                if (now - (skipCool[key] ?: 0L) < 8000L) return@postDelayed
                val clicked = AutoSkip.perform(hit)
                if (clicked != null) {
                    skipCool[key] = now
                    com.adhammer.hook.HitLog.hit(
                        pkg, clicked, "自动跳过",
                        com.adhammer.rules.StatsStore.KIND_SKIP
                    )
                }
            }, delay)
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
