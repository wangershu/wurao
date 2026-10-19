package com.adhammer.float

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 悬浮窗开关控制。
 *
 * 悬浮窗是「制作规则」的入口：实时显示当前 Activity、抓取布局快照、
 * 选择目标 View 后一键生成规则（对应原版的悬浮窗 + 快照 + 锤子流程）。
 */
object FloatController {

    var enabled by mutableStateOf(false)
        private set

    fun syncFrom(context: Context) {
        enabled = canOverlay(context) && running
    }

    fun toggle(context: Context, on: Boolean) {
        if (on) {
            if (!canOverlay(context)) {
                requestOverlay(context)
                return
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, FloatService::class.java))
                } else {
                    context.startService(Intent(context, FloatService::class.java))
                }
            }
            running = true
        } else {
            runCatching { context.stopService(Intent(context, FloatService::class.java)) }
            running = false
        }
        enabled = on && canOverlay(context)
    }

    @Volatile
    var running: Boolean = false

    fun canOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** 无障碍服务是否已开启（自动跳过依赖它） */
    fun accessibilityOn(context: Context): Boolean {
        val expected = "${context.packageName}/${BaJieService::class.java.name}"
        return runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.split(':')
                ?.any { it.equals(expected, ignoreCase = true) } == true
        }.getOrDefault(false)
    }

    private fun requestOverlay(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
