package com.wurao.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.wurao.core.Cfg
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONObject

/**
 * 把界面改的配置送进 hook 进程 —— 走**框架的文件通道**（远程文件）。
 *
 * 为什么不走别的路（这两个坑都是真机上撞出来的）：
 * 1. **ContentProvider**：Android 11+ 包可见性 —— 目标 App 没声明 queries 我们的包，
 *    就看不到我们的 provider（adb shell 能调、美团进程调不到，实测如此）；
 * 2. **远程偏好**：目标进程拿到的是"进程启动那一刻的快照"，会停在历史时点不刷新。
 *
 * 远程文件没有这两个问题：这边写完，目标进程立刻读到最新。
 */
object CfgPush {

    private const val TAG = "Wurao"

    private var service: XposedService? = null

    @Volatile
    var bound: Boolean = false
        private set

    /** 绑定框架服务；绑上就立刻推一次（否则 hook 侧读到的是空）。 */
    fun bind(ctx: Context, onBound: () -> Unit = {}) {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    bound = true
                    Log.i(TAG, "框架服务已连接，推送配置")
                    flush(ctx)
                    onBound()
                }

                override fun onServiceDied(s: XposedService) {
                    service = null
                    bound = false
                }
            })
        }.onFailure { Log.w(TAG, "绑定框架服务失败: ${it.message}") }
    }

    /** 把本地配置整体写进远程文件。配置有任何改动都要调一次。 */
    fun flush(ctx: Context) {
        val s = service ?: run {
            Log.w(TAG, "框架服务未连接，配置只存在本地（hook 读不到）")
            return
        }
        val prefs = ctx.getSharedPreferences(Cfg.GROUP, Context.MODE_PRIVATE)
        val json = JSONObject()
        prefs.all.forEach { (k, v) -> json.put(k, v?.toString() ?: "") }
        runCatching {
            val pfd = s.openRemoteFile(Cfg.CFG_FILE) ?: return
            pfd.use {
                val fd = it.fileDescriptor
                // 远程文件给的是**已存在**的描述符，不截断的话新内容比旧内容短时会留下残字节，
                // JSON 直接解析失败 —— 必须先 ftruncate 再 lseek(0)
                runCatching { Os.ftruncate(fd, 0L) }
                runCatching { Os.lseek(fd, 0L, OsConstants.SEEK_SET) }
                ParcelFileDescriptor.AutoCloseOutputStream(fd.let { pfd }).use { os ->
                    os.write(json.toString().toByteArray(Charsets.UTF_8))
                }
            }
            Log.i(TAG, "配置已推送: ${prefs.all.size} 个键")
        }.onFailure { Log.w(TAG, "推送配置失败: ${it.message}") }
    }
}
