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
import com.adhammer.rules.model.StepAct
import com.adhammer.rules.model.Task
import com.adhammer.util.HLog
import kotlin.math.roundToInt

/**
 * 八戒：无障碍自动化服务。
 *
 * 用途：悟空（Xposed）搞不定的广告 —— 比如按钮没有稳定的类名/资源名、
 * 或者需要先滑动再点击 —— 交给无障碍按步骤模拟操作。
 *
 * 任务来自订阅的 ba_jie_tasks.txt，按 Activity 匹配后顺序执行 [Step]。
 *
 * 一个步骤的**动作类型**由 [Step.at] 决定（[StepAct]），**定位方式**由 [Step.ct] 决定。
 * 早期版本只实现了「坐标点击 + 按 id/文本点击」两种，
 * 结果 at=7（返回键）/ at=11（Home）/ at=6（滑动）/ at=10（命令）这几类步骤
 * 在订阅里占了 42 条，跑起来什么都不会发生 —— 现在补齐了。
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
                        .filter { it.isEnable && it.matchesActivity(a) }
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
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 按顺序执行一个任务的所有步骤 */
    private fun run(task: Task) {
        val steps = task.steps.filter { it.isExecutable() }
        if (steps.isEmpty()) {
            HLog.w("task has no executable step: ${task.pkg}")
            return
        }
        HLog.d("run task: ${task.pkg} steps=${steps.size}")
        com.adhammer.hook.HitLog.hit(
            task.pkg ?: "",
            task.name ?: task.act ?: "任务",
            "自动跳过",
            com.adhammer.rules.StatsStore.KIND_SKIP
        )
        var delay = 0L
        steps.forEach { step ->
            handler.postDelayed({ exec(step) }, delay)
            // 下一步的起始时间 = 本步延迟 + 步与步之间的间隔
            delay += step.d + step.it.coerceAtLeast(120L)
        }
    }

    /** 执行单个步骤。全局动作（返回/Home/命令）不依赖节点树。 */
    private fun exec(step: Step) {
        if (!step.isExecutable()) {
            HLog.w("skip invalid step at=${step.at} ct=${step.ct}: ${step.whyNot()}")
            return
        }
        when {
            step.isBack -> {
                HLog.d("step: global BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            step.isHomePress -> {
                HLog.d("step: global HOME")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            step.isCmdStep -> runCmd(step.cmd!!)
            step.isSwipe -> swipeStep(step)
            step.isPoint -> {
                val (x, y) = resolvePoint(step)
                val n = if (step.isLoop) 1 else step.c.coerceAtLeast(1)
                repeat(n) { clickPoint(x.toFloat(), y.toFloat()) }
            }
            else -> clickNodeStep(step)
        }
    }

    // ---------------- 节点点击（at=8 / at=13） ----------------

    /**
     * 按 ct 找节点然后点击。
     *
     * 广告控件常常是异步渲染出来的，所以带重试：
     * 按 [Step.lt] 上限、[Step.it] 间隔反复找，找到就停。
     * c=-2 表示「循环到找不到为止」（订阅里快手/微博的划走广告用的就是这个）。
     */
    private fun clickNodeStep(step: Step) {
        val times = when {
            step.isLoop -> LOOP_MAX
            step.lt > 0 -> step.lt
            else -> 1
        }
        val gap = step.it.coerceAtLeast(300L)
        repeatTry(times, gap) { attempt ->
            if (attempt > 0 && Snapshot.pkg().isNullOrBlank()) return@repeatTry false
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@repeatTry true
            val target = findNode(root, step)
            if (target == null) {
                if (attempt == 0) HLog.d("step target not found: ${step.vId ?: step.t ?: step.wv}")
                return@repeatTry true      // 继续重试
            }
            val n = if (step.isLoop) 1 else step.c.coerceAtLeast(1)
            repeat(n) {
                if (step.isLong || step.prs) longClickNode(target) else target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            HLog.d("step clicked: ${step.describe()}")
            false   // 成功，停止重试
        }
    }

    /** 重复尝试：block 返回 true 表示「还要继续试」 */
    private fun repeatTry(times: Int, gap: Long, block: (Int) -> Boolean) {
        if (times <= 1) {
            block(0)
            return
        }
        var attempt = 0
        val step = object : Runnable {
            override fun run() {
                val again = block(attempt)
                attempt++
                if (again && attempt < times) handler.postDelayed(this, gap)
            }
        }
        handler.post(step)
    }

    private fun longClickNode(node: AccessibilityNodeInfo) {
        val p = Path()
        val r = Rect()
        node.getBoundsInScreen(r)
        p.moveTo(r.centerX().toFloat(), r.centerY().toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 600))
            .build()
        runCatching { dispatchGesture(g, null, null) }
            .onFailure { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
    }

    // ---------------- 坐标 / 滑动 ----------------

    /**
     * 算出这一步在本机屏幕上真正要点的位置。
     *
     * 订阅里的坐标是制作者那台机器上的绝对像素，直接拿来点必然点空。
     * 如果规则记下了控件 bounds（leftX/rightX/topY/bottomY），
     * 就把 (x,y) 换算成**在 bounds 里的相对比例**，再映射到本机宽高 —— 换机型也能点中。
     */
    private fun resolvePoint(step: Step): Pair<Int, Int> {
        val dm = resources.displayMetrics
        if (step.pRel && step.hasBounds && step.rightX > step.leftX && step.bottomY > step.topY) {
            val rx = (step.x - step.leftX).toFloat() / (step.rightX - step.leftX)
            val ry = (step.y - step.topY).toFloat() / (step.bottomY - step.topY)
            return (rx * dm.widthPixels).roundToInt().coerceIn(0, dm.widthPixels) to
                (ry * dm.heightPixels).roundToInt().coerceIn(0, dm.heightPixels)
        }
        return step.x to step.y
    }

    /** at=6 滑动：sd 决定方向，swipe_custom 用 sx0/sy0 -> sx1/sy1 */
    private fun swipeStep(step: Step) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (x0, y0, x1, y1) = when (step.sd) {
            "top" -> floatArrayOf(w / 2, h * 0.9f, w / 2, h * 0.1f)
            "down" -> floatArrayOf(w / 2, h * 0.1f, w / 2, h * 0.9f)
            "left" -> floatArrayOf(w * 0.8f, h / 2, w * 0.1f, h / 2)
            "right" -> floatArrayOf(w * 0.1f, h / 2, w * 0.8f, h / 2)
            "swipe_custom" -> floatArrayOf(
                step.sx0.toFloat(), step.sy0.toFloat(), step.sx1.toFloat(), step.sy1.toFloat()
            ).also {
                if (step.sx0 < 0 || step.sy0 < 0 || step.sx1 < 0 || step.sy1 < 0) {
                    HLog.w("swipe_custom 坐标不完整，跳过")
                    return
                }
            }
            else -> {
                HLog.w("unknown swipe direction: ${step.sd}")
                return
            }
        }
        val dur = step.spd.takeIf { it > 0 }?.coerceAtMost(5000L) ?: 400L
        HLog.d("step: swipe ${step.sd} dur=$dur")
        swipe(x0, y0, x1, y1, dur)
    }

    // ---------------- shell 命令（at=10） ----------------

    private fun runCmd(cmd: String) {
        // 无障碍服务在主线程，exec 会阻塞，必须丢到后台
        Thread {
            runCatching {
                HLog.d("step: cmd $cmd")
                val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }.onFailure { HLog.w("cmd failed: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    // ---------------- 节点查找 ----------------

    private fun findNode(root: AccessibilityNodeInfo, step: Step): AccessibilityNodeInfo? {
        // ct 决定「主要按什么找」，其余条件作为兜底，与规则编辑器里勾选的一致
        val cands = ArrayList<AccessibilityNodeInfo>()
        when {
            step.byId && !step.vId.isNullOrBlank() -> {
                root.findAccessibilityNodeInfosByViewId(step.vId!!)?.let { cands += it }
            }
            step.byText && !step.t.isNullOrBlank() -> {
                // 订阅里 t 常写成 "直播中#;;进入直播间#;人已下载#" 这种多候选
                step.t!!.split(";").forEach { one ->
                    val key = one.trim().trim('#')
                    if (key.isNotBlank()) {
                        root.findAccessibilityNodeInfosByText(key)?.let { cands += it }
                    }
                }
            }
            step.byClass && !step.wv.isNullOrBlank() -> {
                search(root) { it.className?.toString()?.contains(step.wv!!) == true && it.isClickable }
                    ?.let { cands += it }
            }
        }
        val hit = cands.firstOrNull { !step.ignored(it) } ?: cands.firstOrNull()
        if (hit != null) return hit
        // 兜底：按优先级再试其它定位字段，应对订阅里 ct 标得不准的情况
        if (!step.vId.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByViewId(step.vId!!)?.firstOrNull()?.let { return it }
        }
        if (!step.t.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByText(step.t!!)?.firstOrNull()?.let { return it }
        }
        if (!step.wv.isNullOrBlank()) {
            search(root) { it.className?.toString()?.contains(step.wv!!) == true && it.isClickable }
                ?.let { return it }
        }
        return null
    }

    /** isIgn + ignTxt/ignId：命中忽略条件的节点不要点 */
    private fun Step.ignored(node: AccessibilityNodeInfo): Boolean {
        if (!isIgn) return false
        val txt = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
        if (!ignTxt.isNullOrBlank() && txt.contains(ignTxt!!, true)) return true
        if (!ignId.isNullOrBlank() && node.viewIdResourceName?.contains(ignId!!, true) == true) return true
        return false
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

    // ---------------- 手势 ----------------

    private fun clickPoint(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, duration: Long = 400L) {
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    companion object {
        @Volatile
        var instance: BaJieService? = null

        /** c=-2 循环执行时的兜底次数，防止广告没出现时一直空转 */
        private const val LOOP_MAX = 20
    }
}
