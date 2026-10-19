package com.adhammer.hook

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import com.adhammer.rules.Cfg

/**
 * 跳过成功时的轻提示 —— 复刻原版的 `adTips`。
 *
 * 原版每条规则可以带一句文案，默认「已为你跳过广告」。这里做成两级：
 * - 全局开关 `cfg:tips`（默认开）：关掉就一条都不弹；
 * - 单应用文案 `tips:<pkg>`：留空则用 [DEFAULT_TEXT]。
 *
 * 注意几点：
 * - Toast 必须在主线程，且要尽早弹 —— 广告页马上 finish 了，晚了宿主进程没 UI 可挂；
 * - 加一个 1.2s 的抖动保护：有些 App 一次启动连开好几个广告页，不然会连弹；
 * - 全部异常吞掉，提示失败绝不能影响去广告本身。
 */
internal object SkipTips {

    const val DEFAULT_TEXT = "已为你跳过广告"

    private var lastShownAt = 0L

    fun show(act: Activity, prefs: android.content.SharedPreferences?) {
        if (!Cfg.get(prefs, Cfg.TIPS, true)) return

        val pkg = act.packageName
        val text = HomeConfig.tips(pkg)?.takeIf { it.isNotBlank() } ?: DEFAULT_TEXT

        val now = SystemClock.elapsedRealtime()
        if (now - lastShownAt < 1200L) return
        lastShownAt = now

        runCatching {
            val app = act.applicationContext ?: return
            act.runOnUiThread {
                runCatching { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
