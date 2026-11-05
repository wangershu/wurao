package com.wurao.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.wurao.core.Cfg
import com.wurao.core.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * 注意：XposedServiceHelper 的 listener 是单值，整个 App 只有 [XposedBridge] 注册一次，
 * 这里通过 onBind 订阅，避免互相覆盖导致「永远显示未连接」。
 */
object CfgPush {

    private const val TAG = "Wurao"
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /** 状态统一由 XposedBridge 提供，不再自己维护一份（之前两份不一致）。 */
    val bound: Boolean get() = XposedBridge.bound

    /** 订阅框架绑定事件；绑定后异步推一次配置（不阻塞首帧）。 */
    fun bind(ctx: Context, onBound: () -> Unit = {}) {
        val app = ctx.applicationContext
        XposedBridge.onBind {
            Log.i(TAG, "框架服务已连接，推送配置")
            flush(app)
            onBound()
        }
    }

    /** 当前作用域（框架里勾了哪些 App）。不在作用域里的 App 一条 hook 都不会装。 */
    fun scope(): List<String> =
        runCatching { XposedBridge.current()?.getScope() ?: emptyList() }.getOrDefault(emptyList())

    /** 框架名与版本，排障时用来确认模块确实被加载了。 */
    fun framework(): String = runCatching {
        val s = XposedBridge.current() ?: return@runCatching "未连接"
        "${s.frameworkName} ${s.frameworkVersion}"
    }.getOrDefault("未连接")

    /** 去抖：多处调用只写一次远程文件。 */
    @Volatile private var pendingFlush: Job? = null

    /** 把本地配置整体写进远程文件。改到后台线程，避免冷启动时卡住主线程。 */
    fun flush(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            pendingFlush?.cancel()
            pendingFlush = ioScope.launch {
                delay(300)
                doFlush(app)
            }
        }
    }

    private fun doFlush(ctx: Context) {
        val s = XposedBridge.current() ?: run {
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
