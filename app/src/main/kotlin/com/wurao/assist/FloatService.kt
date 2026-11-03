package com.wurao.assist

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮球：一个小胶囊贴在屏幕边缘，随时点一下就开启抓屏框选。
 *
 * 不用每次回应用里点「抓一屏」—— 你就停在目标 App 里，点悬浮球就行。
 */
class FloatService : Service() {

    private var wm: WindowManager? = null
    private var ball: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showBall()
        return START_STICKY
    }

    private fun showBall() {
        if (ball != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = TextView(this).apply {
            text = "框"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.argb(210, 30, 30, 34))
            }
            setPadding(40, 26, 40, 26)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        lp.x = 16

        // 拖动：能挪动位置；点击（没挪动）→ 开启抓屏
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    lp.x = startX - dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm?.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 点一下：开启抓屏（画出目标 App 的控件框，点选生成规则）
                        Log.i("Wurao", "悬浮球被点：开启抓屏")
                        SnapshotService.instance?.let { svc ->
                            val picks = svc.captureNow()
                            if (picks.isNotEmpty()) FrameOverlay.show(svc, picks) { pick, act ->
                                com.wurao.core.store.RuleStore.upsert(
                                    com.wurao.core.analyze.RuleGen.rules(pick.pkg ?: "", pick.node, pick.container, act).first()
                                )
                                com.wurao.ui.CfgPush.flush(svc)
                                android.widget.Toast.makeText(svc, "已存：${com.wurao.core.analyze.RuleGen.rules(pick.pkg ?: "", pick.node, pick.container, act).first().target}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } ?: android.widget.Toast.makeText(this, "抓屏服务没开（去无障碍设置里开一下）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching {
            wm?.addView(v, lp)
            ball = v
            Log.i("Wurao", "悬浮球已显示")
        }.onFailure { Log.w("Wurao", "悬浮球没挂上（没悬浮窗权限？）", it) }
    }

    override fun onDestroy() {
        ball?.let { runCatching { wm?.removeView(it) } }
        ball = null
        super.onDestroy()
    }
}
