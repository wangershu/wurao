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

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for (p in picks) {
            val r = p.bounds
            if (r.width() <= 0 || r.height() <= 0) continue
            // 只画描边，不填充 —— 填充会盖住目标 App，根本看不清框的是哪个控件
            c.drawRect(r, box)
            // 名字只在选中的那个上画（满屏都是名字就看不清了）
            if (p === selected) {
                val name = p.node.text ?: p.node.desc ?: p.node.vid ?: p.node.className.substringAfterLast('.')
                c.drawText(name.take(18), r.left.toFloat() + 4, r.top.toFloat() + 22, label)
            }
        }
        // 选中的那个高亮
        selected?.let { p ->
            val r = p.bounds
            c.drawRect(r, Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.argb(255, 103, 80, 164)
            })
        }

        val barH = if (selected != null) 190 else 120
        val top = (height - barH).toFloat()
        c.drawRect(0f, top, width.toFloat(), height.toFloat(), bar)
        val sg = suggest
        val act = sg?.first
        val why = sg?.second
        if (selected == null) {
            c.drawText("点一个控件选中它，点没有控件的空白处取消", 32f, top + 55f, barText)
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
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x
        val y = e.y
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
