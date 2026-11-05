package com.wurao.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wurao.core.model.Act

/**
 * 框选 overlay：照抄旧版 Inspector 的设计。
 * - FrameView 画框：细框按索引取色，选中后其余压暗，悬停十字准星
 * - 把手可拖动：「拖动挪开 · 点一下收起」，把手里有「退出」和「回到底部」
 * - 退出按钮在把手里（不遮挡点选）
 */
object FrameOverlay {

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null

    fun show(ctx: Context, picks: List<SnapshotService.Pick>, onPick: (SnapshotService.Pick, Act) -> Unit) {
        if (picks.isEmpty()) {
            Toast.makeText(ctx, "没抓到控件", Toast.LENGTH_SHORT).show()
            return
        }
        hide(ctx)
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w

        val frame = FrameView(ctx, picks, onPick)
        val r = FrameLayout(ctx)
        r.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 提示框：可拖动把手 + 内容（建议 + 按建议做/取消），默认贴底
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val handle = dragHandle(ctx, wrap)
        wrap.addView(handle)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0xF21C1C1E.toInt())   // 不透明
            }
        }
        wrap.addView(content)
        frame.panelContent = content

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(dp(ctx, 10), 0, dp(ctx, 10), dp(ctx, 16))
        r.addView(wrap, lp)

        // 用 TYPE_ACCESSIBILITY_OVERLAY（无障碍 overlay）—— 坐标系和无障碍的 bounds 一致；
        // TYPE_APPLICATION_OVERLAY 的坐标系会差一截（状态栏/导航栏偏移），框就对不上
        val type = if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { w.addView(r, wlp); root = r }
    }

    fun hide(ctx: Context) {
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 把手：可拖动（挪开 / 回到底部），里面有「退出」和「回到底部」。照抄旧版。 */
    private fun dragHandle(ctx: Context, host: View): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0xF21C1C1E.toInt())   // 不透明（之前 0xE6 半透明看不清）
            }
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 8), dp(ctx, 5))
        }
        val grip = TextView(ctx).apply {
            text = "⠿  拖动挪开 · 点一下收起"
            textSize = 11f
            setTextColor(0xFF9A9A9A.toInt())
        }
        bar.addView(grip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(chip(ctx, "回到底部") {
            host.animate().translationX(0f).translationY(0f).setDuration(200).start()
        })
        bar.addView(chip(ctx, "退出", primary = true) { hide(ctx) })

        var lastX = 0f; var lastY = 0f; var baseX = 0f; var baseY = 0f; var moved = false
        val slop = dp(ctx, 4).toFloat()
        bar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                    baseX = host.translationX; baseY = host.translationY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX; val dy = e.rawY - lastY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    host.translationX = baseX + dx
                    host.translationY = baseY + dy
                    true
                }
                else -> true
            }
        }
        return bar
    }

    private fun chip(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit): View {
        val v = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (primary) Color.WHITE else 0xFFCCCCCC.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(if (primary) 0xFF6750A4.toInt() else 0x33000000)
            }
            setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(ctx, 8)
        v.layoutParams = lp
        return v
    }

    /** 画框的 View：细框按索引取色、选中压暗、悬停十字准星、名字只在选中时画。照抄旧版。 */
    private class FrameView(
        ctx: Context,
        private val picks: List<SnapshotService.Pick>,
        private val onPick: (SnapshotService.Pick, Act) -> Unit,
    ) : View(ctx) {

        var panelContent: LinearLayout? = null
        private var selected: SnapshotService.Pick? = null
        /** 同一点命中的全部层级，按面积从细到粗；0 = 最细的控件。 */
        private var levelPicks: List<SnapshotService.Pick> = emptyList()
        private var levelIndex = 0
        private var touchX = -1f
        private var touchY = -1f

        private val palette = intArrayOf(
            0xFF67B0FF.toInt(), 0xFFFF8A65.toInt(), 0xFF81C784.toInt(), 0xFFBA68C8.toInt(),
            0xFF4DD0E1.toInt(), 0xFFFFD54F.toInt(),
        )
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF6750A4.toInt() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; setShadowLayer(3f, 1f, 1f, Color.BLACK) }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val dim = selected != null
            picks.forEachIndexed { idx, p ->
                if (p === selected) return@forEachIndexed
                val r = p.bounds
                if (r.width() <= 0 || r.height() <= 0) return@forEachIndexed
                stroke.color = palette[idx % palette.size]
                stroke.alpha = if (dim) 0x46 else 0xC8
                c.drawRect(r, stroke)
            }
            if (touchX >= 0f) {
                pickAt(touchX.toInt(), touchY.toInt())?.let { p ->
                    c.drawRect(p.bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF8A8A8A.toInt()
                    })
                }
                c.drawLine(touchX - 20, touchY, touchX + 20, touchY, label)
                c.drawLine(touchX, touchY - 20, touchX, touchY + 20, label)
            }
            selected?.let { p ->
                c.drawRect(p.bounds, selStroke)
                val name = p.node.text ?: p.node.desc ?: p.node.vid ?: p.node.className.substringAfterLast('.')
                c.drawText(name.take(18), p.bounds.left.toFloat() + 4, p.bounds.top.toFloat() + 22, label)
            }
        }

        /** 多个框重叠时选面积最小的（最细的那个控件），别一上来就选到大容器。 */
        private fun pickAt(x: Int, y: Int): SnapshotService.Pick? =
            picks.filter { it.bounds.contains(x, y) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }

        private fun nodeLabel(n: com.wurao.core.analyze.RuleGen.NodeInfo): String {
            val v = n.vid?.let { "vid=$it" }
                ?: n.text?.takeIf { it.isNotBlank() }?.let { "text=$it" }
                ?: n.desc?.takeIf { it.isNotBlank() }?.let { "desc=$it" }
                ?: n.className.substringAfterLast('.')
            return if (v.length > 28) v.take(28) + "…" else v
        }

        private fun suggestFor(p: SnapshotService.Pick): Triple<String, String, Act> {
            val txt = p.node.text ?: p.node.desc ?: ""
            val skipLike = txt.contains("跳过") || txt.contains("关闭") || txt.contains("Skip", true) || txt.contains("跳过广告")
            return if (skipLike) Triple("点掉", "文案里写着『$txt』，像是要你点的那种", Act.CLICK)
            else Triple("藏起来", "不像要点的，更像要藏掉的东西（${txt.ifBlank { p.node.vid ?: p.node.className.substringAfterLast('.') }}）", Act.HIDE)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            touchX = e.x; touchY = e.y
            if (e.action == MotionEvent.ACTION_UP) { touchX = -1f; touchY = -1f }
            invalidate()
            if (e.action != MotionEvent.ACTION_UP) return true
            val x = e.x.toInt(); val y = e.y.toInt()
            levelPicks = picks.filter { it.bounds.contains(x, y) }
                .sortedBy { it.bounds.width().toLong() * it.bounds.height() }
            levelIndex = 0
            val hit = levelPicks.firstOrNull()
            if (hit == null) {
                levelPicks = emptyList()
                selected = null
                panelContent?.removeAllViews()
                invalidate()
                return true
            }
            selected = hit
            showSuggest(hit)
            invalidate()
            return true
        }

        private fun showSuggest(p: SnapshotService.Pick) {
            val content = panelContent ?: return
            content.removeAllViews()
            val ctx = content.context
            val (act, why, actEnum) = suggestFor(p)
            content.addView(TextView(ctx).apply {
                text = "建议：$act —— $why"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 6))
            })
            // 层级面包屑：当前层 + 往上两层，看清选中的是哪一层
            val total = levelPicks.size.coerceAtLeast(1)
            val chain = levelPicks.drop(levelIndex).take(3)
                .mapIndexed { i, pk ->
                    if (i == 0) "[${nodeLabel(pk.node)}]" else nodeLabel(pk.node)
                }.joinToString(" › ")
            content.addView(TextView(ctx).apply {
                text = "层级 ${levelIndex + 1}/$total：$chain"
                setTextColor(0xFFBBBBBB.toInt())
                textSize = 11f
                setPadding(dp(ctx, 14), 0, dp(ctx, 14), dp(ctx, 6))
            })
            val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            if (levelIndex > 0) btnRow.addView(chip(ctx, "− 细") { moveLevel(-1) })
            if (levelIndex < levelPicks.lastIndex) btnRow.addView(chip(ctx, "+ 粗") { moveLevel(1) })
            btnRow.addView(chip(ctx, "按建议做", primary = true) {
                onPick(p, actEnum)
                val svc = com.wurao.assist.SnapshotService.instance
                if (actEnum == Act.CLICK) {
                    // 点掉：立即点一下（dispatchGesture）
                    svc?.let {
                        val cx = p.bounds.centerX().toFloat()
                        val cy = p.bounds.centerY().toFloat()
                        val path = android.graphics.Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
                        it.dispatchGesture(
                            android.accessibilityservice.GestureDescription.Builder()
                                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                                .build(), null, null,
                        )
                    }
                }
                // 藏起来：只存规则，不画遮盖（遮盖会一直遮住、切换 App 还在，不对）。
                // 真正的隐藏由引擎下次进入时做（view.visibility = GONE）。
                FrameOverlay.hide(ctx)
            })
            btnRow.addView(chip(ctx, "取消") {
                selected = null
                levelPicks = emptyList()
                levelIndex = 0
                content.removeAllViews()
                invalidate()
            })
            content.addView(btnRow)
        }

        /** 前后连续调层级：往细（−）或往粗（+），可以一直点到想要的那一层。 */
        private fun moveLevel(delta: Int) {
            val next = (levelIndex + delta).coerceIn(0, levelPicks.lastIndex)
            if (next == levelIndex || levelPicks.isEmpty()) return
            levelIndex = next
            selected = levelPicks[next]
            showSuggest(selected!!)
            invalidate()
        }
    }
}
