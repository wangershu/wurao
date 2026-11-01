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
    private val onPick: (SnapshotService.Pick) -> Unit,
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

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for (p in picks) {
            val r = p.bounds
            if (r.width() <= 0 || r.height() <= 0) continue
            c.drawRect(r, fill)
            c.drawRect(r, box)
            val name = p.node.text ?: p.node.desc ?: p.node.vid ?: p.node.className.substringAfterLast('.')
            c.drawText(name.take(18), r.left.toFloat() + 4, r.top.toFloat() + 22, label)
        }
        // 提示：点一下就选，再点空白处取消
        c.drawText("点控件选中，退到后台或再抓一次会关掉这个框", 24f, height - 24f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val hit = pickAt(e.x.toInt(), e.y.toInt())
        if (hit == null) {
            Toast.makeText(ctx, "这里没有控件，再点别处", Toast.LENGTH_SHORT).show()
            return true
        }
        onPick(hit)
        return true
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
        fun show(ctx: Context, picks: List<SnapshotService.Pick>, onPick: (SnapshotService.Pick) -> Unit): Boolean {
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
