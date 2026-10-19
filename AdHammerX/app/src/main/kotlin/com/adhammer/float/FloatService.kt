package com.adhammer.float

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.adhammer.ui.AppTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults

/**
 * 悬浮窗服务：显示当前 Activity / 包名，并提供快照入口。
 *
 * 这是「制作规则」的起点：
 * 1. 悬浮窗一行行显示当前页面（Activity 名）—— 确定目标页面
 * 2. 点「快照」把当前页面元素解析出来 —— 确定点击/屏蔽对象
 * 3. 选中目标后生成规则 JSON —— 可直接保存或交给 AI 润色
 */
class FloatService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var registry: LifecycleRegistry
    private lateinit var savedState: SavedStateRegistryController
    private var windowManager: WindowManager? = null
    private var view: ComposeView? = null

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        registry = LifecycleRegistry(this)
        savedState = SavedStateRegistryController.create(this)
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        startForegroundCompat()
        showWindow()
        FloatController.running = true
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            val n = android.app.Notification.Builder(this, CHANNEL)
                .setContentTitle("大圣净化悬浮窗")
                .setContentText("正在显示当前页面信息")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .build()
            startForeground(1001, n)
        }
    }

    private fun showWindow() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatService)
            setViewTreeSavedStateRegistryOwner(this@FloatService)
            setContent {
                AppTheme {
                    FloatPanel(
                        onSnapshot = { doSnapshot() },
                        onClose = { FloatController.toggle(this@FloatService, false) }
                    )
                }
            }
        }
        runCatching { wm.addView(view, params) }
            .onFailure { Toast.makeText(this, "悬浮窗显示失败：${it.message}", Toast.LENGTH_SHORT).show() }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    /** 快照：把当前窗口的 View 树 dump 出来，供生成规则 */
    private fun doSnapshot() {
        val root = Snapshot.capture()
        if (root.isNullOrBlank()) {
            Toast.makeText(this, "未获取到当前页面，请确认无障碍服务已开启", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val intent = Intent(this, com.adhammer.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("snapshot", root)
            }
            startActivity(intent)
        }
        Toast.makeText(this, "已抓取布局快照", Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { view?.let { windowManager?.removeView(it) } }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        FloatController.running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "float_window"
    }
}

@androidx.compose.runtime.Composable
private fun FloatPanel(onSnapshot: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .background(Color(0xCC000000))
            .padding(12.dp)
    ) {
        Text(text = "大圣净化", color = Color.White, fontSize = 16.sp)
        Text(
            text = Snapshot.currentPage(),
            color = Color(0xFFCCCCCC),
            fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .verticalScroll(rememberScrollState())
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(text = "快照", onClick = onSnapshot, colors = ButtonDefaults.textButtonColors())
            TextButton(text = "关闭", onClick = onClose, colors = ButtonDefaults.textButtonColors())
        }
    }
}
