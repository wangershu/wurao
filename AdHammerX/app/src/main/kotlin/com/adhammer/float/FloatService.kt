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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
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

/**
 * 悬浮窗控制条：制作规则的入口。
 *
 * 原版这一块的流程是「悬浮窗 → 快照 → 查看布局 → 勾选节点 → 生成规则」，
 * 这里把它压成三步：
 *
 * 1. **快照**：抓当前页面的 View 树 → 直接跳进规则工厂（不再需要自己找入口）
 * 2. **取点**：整屏铺一层透明取点层，点哪读哪的坐标 → 同样是八戒步骤的原料
 * 3. 无障碍没开时，面板直接把「去开启」摆在最显眼的位置
 *
 * 之前「点了没反应」的根因有两个：无障碍没开时只弹一句 toast、
 * 抓完快照只把文本塞进 Intent 但没人负责跳到工厂页。两个都修了。
 */
class FloatService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var registry: LifecycleRegistry
    private lateinit var savedState: SavedStateRegistryController
    private var windowManager: WindowManager? = null
    private var view: ComposeView? = null
    private var picker: View? = null

    /** 面板要跟着当前页面和无障碍状态变，所以这两个得是 state，不能只在 setContent 时算一次 */
    private val pageState = androidx.compose.runtime.mutableStateOf(Snapshot.currentPage())
    private val a11yState = androidx.compose.runtime.mutableStateOf(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            pageState.value = Snapshot.currentPage()
            a11yState.value = FloatController.accessibilityOn(this@FloatService)
            handler.postDelayed(this, 800)
        }
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        registry = LifecycleRegistry(this)
        savedState = SavedStateRegistryController.create(this)
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        startForegroundCompat()
        a11yState.value = FloatController.accessibilityOn(this)
        showWindow()
        FloatController.running = true
        handler.post(ticker)
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            val n = android.app.Notification.Builder(this, CHANNEL)
                .setContentTitle("大圣净化悬浮窗")
                .setContentText("点「快照」抓当前页面，点「取点」拿坐标")
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
                        page = pageState.value,
                        a11yOn = a11yState.value,
                        onSnapshot = { doSnapshot() },
                        onPick = { startPick() },
                        onOpenA11y = { openAccessibilitySettings() },
                        onClose = { FloatController.toggle(this@FloatService, false) }
                    )
                }
            }
        }
        runCatching { wm.addView(view, params) }
            .onFailure {
                Toast.makeText(this, "悬浮窗显示失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    /** 快照：抓当前窗口 → 直接跳到规则工厂 */
    private fun doSnapshot() {
        if (!FloatController.accessibilityOn(this)) {
            toast("需要先开启无障碍服务才能抓布局")
            openAccessibilitySettings()
            return
        }
        // 区分三种「抓不到」，否则用户只会看到一句没用的「没抓到」
        if (BaJieService.instance == null) {
            toast("无障碍服务还没连上：关掉再开一次本应用的无障碍开关")
            return
        }
        val cap = Snapshot.capture()
        if (cap == null) {
            toast("没抓到节点：请停在目标 App 的页面上再点，别停在桌面")
            return
        }
        toast("已抓到 ${cap.nodes.size} 个节点 → 打开规则工厂")
        openFactory()
    }

    private fun openFactory() {
        runCatching {
            val intent = Intent(this, com.adhammer.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("dest", "factory")
            }
            startActivity(intent)
        }.onFailure { toast("打开规则工厂失败：${it.message}") }
    }

    /**
     * 取点：铺一层整屏透明层，抬手即读坐标。
     *
     * 必须能收到触摸，所以不能加 FLAG_NOT_TOUCHABLE；加半透明底色是为了
     * 让用户看得见自己点在哪，取完立刻移除，不会挡住真正的那次点击。
     */
    private fun startPick() {
        if (picker != null) return
        val wm = windowManager ?: return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val v = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    stopPick()
                    Snapshot.pick(x, y)
                    toast("已取坐标 ($x, $y)")
                    openFactory()
                }
                return true
            }
        }
        v.setBackgroundColor(0x2200A0FF)
        runCatching { wm.addView(v, lp) }.onFailure {
            toast("取点层添加失败：${it.message}")
            return
        }
        picker = v
        toast("点屏幕取坐标（会截一次点击）")
    }

    private fun stopPick() {
        picker?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        picker = null
    }

    private fun openAccessibilitySettings() {
        runCatching {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopPick()
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
private fun FloatPanel(
    page: String,
    a11yOn: Boolean,
    onSnapshot: () -> Unit,
    onPick: () -> Unit,
    onOpenA11y: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(208.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6101010))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "大圣净化",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "关闭",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
                modifier = Modifier.clickable { onClose() }
            )
        }
        Text(
            text = page,
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (!a11yOn) {
            Text(
                text = "无障碍未开启，抓不到布局",
                color = Color(0xFFFF7A7A),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            if (a11yOn) {
                FloatAction("快照", onSnapshot)
                FloatAction("取点", onPick)
            } else {
                FloatAction("去开启无障碍", onOpenA11y)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FloatAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color(0xFF6FB4FF),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(end = 16.dp)
            .clickable { onClick() }
    )
}
