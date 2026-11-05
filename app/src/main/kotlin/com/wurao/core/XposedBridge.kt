package com.wurao.core

import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧与 LSPosed 框架的连接（libxposed service 102）。
 *
 * 拿它做两件事：
 * - getScope()：LSPosed 里用户勾选的应用 → 决定哪些规则要落位
 * - getRemotePreferences(name)：写框架托管的配置，hook 侧用同名 API 读
 *
 * 一次性落位，不是运行时同步：无扰不需要常驻后台，不耗电。
 */
object XposedBridge {

    @Volatile private var svc: XposedService? = null

    /**
     * XposedServiceHelper 的 mListener 是**单值**：后注册会覆盖先注册的。
     * 所以整个 App 只能有一处 registerListener，其他模块用 onBind 订阅回调。
     */
    private val bindCallbacks =
        java.util.concurrent.CopyOnWriteArrayList<(XposedService) -> Unit>()

    /** 上次写入的内容，内容没变就不重复写（避免几十次同步 binder 提交）。 */
    private val lastWritten = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastSkipLogMs = 0L

    fun init() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                svc = s
                Log.i("Wurao", "XposedService bound, scope=${runCatching { s.scope }.getOrNull()?.size}")
                bindCallbacks.forEach { cb -> runCatching { cb(s) } }
            }

            override fun onServiceDied(s: XposedService) {
                svc = null
            }
        })
    }

    /** 订阅绑定事件；若已绑定则立即回放一次（解决注册时序问题）。 */
    fun onBind(cb: (XposedService) -> Unit) {
        bindCallbacks += cb
        svc?.let { runCatching { cb(it) } }
    }

    /** 给 CfgPush 等模块复用同一个 service。 */
    fun current(): XposedService? = svc

    /** LSPosed 里勾选的应用；拿不到时返回空（表示不过滤）。 */
    fun scope(): Set<String> = runCatching { svc?.scope?.toSet() ?: emptySet() }.getOrDefault(emptySet())

    val bound: Boolean get() = svc != null

    /** 落位：控件规则按应用切分，域名规则全存一份。 */
    fun land(byPkg: Map<String, List<String>>, domains: List<String>) {
        val s = svc ?: run {
            // 未绑定时日志降频，别刷屏拖垮 logcat
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSkipLogMs > 5000) {
                lastSkipLogMs = now
                Log.w("Wurao", "land skipped: XposedService not bound")
            }
            return
        }
        runCatching {
            var changed = 0
            byPkg.forEach { (pkg, targets) ->
                val key = "wurao_rules_$pkg"
                val content = targets.joinToString("\n") { b64(it) }
                if (lastWritten[key] == content) return@forEach
                s.getRemotePreferences(key).edit().putString("rules", content).commit()
                lastWritten[key] = content
                changed++
            }
            val domContent = domains.joinToString("\n")
            // 域名表为空时不覆盖已有数据：订阅导入中间态可能读不到域名文件，
            // 之前就是这样把 3.4 万条域名冲成空串的
            if (domContent.isNotBlank() && lastWritten["wurao_domains"] != domContent) {
                s.getRemotePreferences("wurao_domains")
                    .edit().putString("domains", domContent).commit()
                lastWritten["wurao_domains"] = domContent
                changed++
            }
            if (changed > 0)
                Log.i("Wurao", "landed ${byPkg.size} apps, ${domains.size} domains (changed=$changed)")
        }.onFailure { Log.w("Wurao", "land failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
