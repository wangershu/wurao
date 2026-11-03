package com.wurao.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * 控件框：**把抓到的控件直接框在目标界面上，点哪个选哪个**（照 GKD 那套）。
 *
 * 之前做成"列一张清单让你挑"，问题是清单里前几屏全是外层容器（没文本没 id），
 * 你根本不知道自己点的是什么。框在当前界面上就没有这个歧义。
 *
 * 用无障碍服务的 `TYPE_ACCESSIBILITY_OVERLAY` 画 —— 不需要悬浮窗权限。
 */
class FrameOverlay(
    private val ctx: Context,
    private val picks: List<SnapshotService.Pick>,
    private val onPick: (SnapshotService.Pick, com.wurao.core.model.Act) -> Unit,
) : View(ctx) {

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(200, 255, 87, 34)
    }
    private val fill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(28, 255, 87, 34)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val bar = Paint().apply { color = Color.argb(235, 28, 28, 30) }
    private val barBtn = Paint().apply { color = Color.argb(255, 103, 80, 164) }
    private val barBtnCancel = Paint().apply { color = Color.argb(120, 90, 90, 95) }
    private val barText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }

    /** 当前选中的控件（点了之后不是立刻存，先在底栏给建议）。 */
    private var selected: SnapshotService.Pick? = null
    private val suggest: Triple<String, String, com.wurao.core.model.Act>?
        get() = selected?.let { suggestFor(it) }

    private val palette = intArrayOf(
        0xFF67B0FF.toInt(), 0xFFFF8A65.toInt(), 0xFF81C784.toInt(), 0xFFBA68C8.toInt(),
        0xFF4DD0E1.toInt(), 0xFFFFD54F.toInt(),
    )
    private var touchX = -1f
    private var touchY = -1f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        // 细框 + 按深度取色；选中一个之后，其余框压暗（不然几十个框看不出选的是哪个）
        val dim = selected != null
        picks.forEachIndexed { idx, p ->
            if (p === selected) return@forEachIndexed
            val r = p.bounds
            if (r.width() <= 0 || r.height() <= 0) return@forEachIndexed
            val col = palette[idx % palette.size]
            c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = col
                alpha = if (dim) 0x46 else 0xC8
            })
        }
        // 悬停高亮（跟随手指）：用户能确认「我现在点的是这儿」
        if (touchX >= 0f) {
            pickAt(touchX.toInt(), touchY.toInt())?.let { p ->
                c.drawRect(p.bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF8A8A8A.toInt()
                })
            }
            c.drawLine(touchX - 20, touchY, touchX + 20, touchY, label)
            c.drawLine(touchX, touchY - 20, touchX, touchY + 20, label)
        }
        // 选中的那个高亮 + 名字
        selected?.let { p ->
            c.drawRect(p.bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF6750A4.toInt()
            })
            val name = p.node.text ?: p.node.desc ?: p.node.vid ?: p.node.className.substringAfterLast('.')
            c.drawText(name.take(18), p.bounds.left.toFloat() + 4, p.bounds.top.toFloat() + 22, label)
        }

        // 右上角退出
        c.drawRect(width - 130f, 40f, width - 16f, 130f, bar)
        c.drawText("× 退出", width - 112f, 105f, barText)

        // 底栏（建议 + 按建议做 / 取消）
        val barH = if (selected != null) 190 else 120
        val top = (height - barH).toFloat()
        c.drawRect(0f, top, width.toFloat(), height.toFloat(), bar)
        val sg = suggest
        val act = sg?.first
        val why = sg?.second
        if (selected == null) {
            c.drawText("点一个控件选中它；点右上角退出", 32f, top + 55f, barText)
        } else {
            c.drawText("建议：$act —— $why", 32f, top + 55f, barText)
            val w = width.toFloat()
            c.drawRect(w * 0.42f, top + 90f, w * 0.72f, top + 160f, barBtn)
            c.drawRect(w * 0.75f, top + 90f, w * 0.97f, top + 160f, barBtnCancel)
            c.drawText("按建议做", w * 0.50f, top + 134f, barText)
            c.drawText("取消", w * 0.83f, top + 134f, barText)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 记录手指位置（画十字准星），抬起时清空
        touchX = e.x; touchY = e.y
        if (e.action == MotionEvent.ACTION_UP) { touchX = -1f; touchY = -1f }
        invalidate()
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x
        val y = e.y
        // 右上角退出
        if (x >= width - 120 && y <= 120) {
            hide(ctx)
            return true
        }
        val sel = selected
        if (sel != null) {
            val barH = 190
            val top = height - barH
            if (y >= top) {
                val w = width.toFloat()
                if (x >= w * 0.42f && x <= w * 0.72f) {
                    // 按建议做：用建议的动作（点掉/藏起来），不是固定隐藏
                    val act = suggestFor(sel).third
                    onPick(sel, act)
                    return true
                }
                if (x >= w * 0.75f) {
                    selected = null
                    invalidate()
                    return true
                }
                return true
            }
        }

        val hit = pickAt(x.toInt(), y.toInt())
        if (hit == null) {
            // 点空白处 = 取消。不给退路的话框会一直盖在人家界面上，你连退都退不掉
            Toast.makeText(ctx, "已取消", Toast.LENGTH_SHORT).show()
            hide(ctx)
            return true
        }
        // 选中，但**不立刻存** —— 先在底栏给一句建议和理由，让你确认
        selected = hit
        invalidate()
        return true
    }

    /** 给一句人话建议 + 理由 + 建议动作（旧版的做法，别让人盲选动作）。 */
    private fun suggestFor(p: SnapshotService.Pick): Triple<String, String, com.wurao.core.model.Act> {
        val txt = p.node.text ?: p.node.desc ?: ""
        val skipLike = txt.contains("跳过") || txt.contains("关闭") || txt.contains("Skip", true) ||
            txt.contains("跳过广告")
        return if (skipLike) {
            Triple("点掉", "文案里写着『$txt』，像是要你点的那种", com.wurao.core.model.Act.CLICK)
        } else {
            Triple("藏起来", "不像要点的，更像要藏掉的东西（${txt.ifBlank { p.node.vid ?: p.node.className.substringAfterLast('.') }}）", com.wurao.core.model.Act.HIDE)
        }
    }

    /** 命中的控件：取包含该点且面积最小的那个（最靠里的那个才是你看到的）。 */
    private fun pickAt(x: Int, y: Int): SnapshotService.Pick? {
        var best: SnapshotService.Pick? = null
        var bestArea = Int.MAX_VALUE
        for (p in picks) {
            val r = p.bounds
            if (r.width() <= 0 || r.height() <= 0) continue
            if (x < r.left || x > r.right || y < r.top || y > r.bottom) continue
            val area = r.width() * r.height()
            if (area < bestArea) {
                bestArea = area
                best = p
            }
        }
        return best
    }

    companion object {
        private var current: FrameOverlay? = null

        /** 显示控件框。 */
        fun show(ctx: Context, picks: List<SnapshotService.Pick>, onPick: (SnapshotService.Pick, com.wurao.core.model.Act) -> Unit): Boolean {
            if (picks.isEmpty()) return false
            hide(ctx)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
            val v = FrameOverlay(ctx, picks, onPick)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            return runCatching {
                wm.addView(v, lp)
                current = v
                true
            }.getOrDefault(false)
        }

        fun hide(ctx: Context) {
            val v = current ?: return
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            runCatching { wm.removeView(v) }
            current = null
        }
    }
}
