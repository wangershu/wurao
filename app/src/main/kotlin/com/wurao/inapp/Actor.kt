package com.wurao.inapp

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.wurao.core.model.Act

/**
 * 动作执行。**只在这里碰 View，别的地方不许直接改界面。**
 *
 * 两条硬规矩：
 * 1. **必须在主线程**：View 只能被 UI 线程碰，任何线程进来都要先 post 过去；
 * 2. **降级是执行细节**：点击先试语义点击（`performClick()`），失败才退坐标派发 ——
 *    这条降级链不进动作枚举（枚举里 CLICK 就只有 CLICK）。
 */
object Actor {

    private val main = Handler(Looper.getMainLooper())

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 执行动作，返回是否真的做到了（没做到不能记进次数，否则次数上限形同虚设）。 */
    fun perform(act: Act, v: View): Boolean = when (act) {
        Act.CLICK -> click(v)
        Act.LONG_CLICK -> runCatching { v.performLongClick() }.getOrDefault(false)
        Act.HIDE -> {
            hide(v)
            true
        }

        Act.REMOVE -> remove(v)
        // BACK 需要 Activity（要调 onBackPressed），由调用方处理，这里明确说做不到
        Act.BACK, Act.BLOCK, Act.GOTO -> false
    }

    fun click(v: View): Boolean {
        if (runCatching { v.performClick() }.getOrDefault(false)) return true
        return clickByTouch(v)
    }

    /**
     * 坐标派发兜底：算中心点，往 root 上派发 DOWN/UP。
     * 只在语义点击失败时用（自定义 View 常常没接 performClick）。
     */
    private fun clickByTouch(v: View): Boolean {
        if (v.width <= 0 || v.height <= 0) return false
        val root = v.rootView ?: return false
        return runCatching {
            val a = IntArray(2)
            val b = IntArray(2)
            v.getLocationOnScreen(a)
            root.getLocationOnScreen(b)
            val x = a[0] - b[0] + v.width / 2f
            val y = a[1] - b[1] + v.height / 2f
            val now = android.os.SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
            try {
                root.dispatchTouchEvent(down) && root.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }.getOrDefault(false)
    }

    /**
     * 隐藏：GONE + 尺寸归零 + padding 归零 + 透明。
     * 四重保险，确保既不占位也不闪一下（"隐藏整个入口"就用这个）。
     */
    fun hide(v: View) {
        runCatching {
            v.layoutParams?.let { lp ->
                if (lp.width > 0 || lp.height > 0) {
                    lp.width = 0
                    lp.height = 0
                    if (lp is ViewGroup.MarginLayoutParams) lp.setMargins(0, 0, 0, 0)
                    v.layoutParams = lp
                }
            }
            v.visibility = View.GONE
            v.setPadding(0, 0, 0, 0)
            v.alpha = 0f
        }
    }

    /** 从父容器摘除。比隐藏更彻底，但会改父容器的子结构 —— 只在明确要求时用。 */
    fun remove(v: View): Boolean = runCatching {
        (v.parent as? ViewGroup)?.removeView(v)
        true
    }.getOrDefault(false)
}
