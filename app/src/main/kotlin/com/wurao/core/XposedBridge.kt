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
    @Volatile private var onReady: (() -> Unit)? = null

    fun init(afterBind: () -> Unit = {}) {
        onReady = afterBind
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                svc = s
                Log.i("Wurao", "XposedService bound, scope=${runCatching { s.scope }.getOrNull()?.size}")

                onReady?.invoke()
            }

            override fun onServiceDied(s: XposedService) {
                svc = null
            }
        })
    }

    /** LSPosed 里勾选的应用；拿不到时返回空（表示不过滤）。 */
    fun scope(): Set<String> = runCatching { svc?.scope?.toSet() ?: emptySet() }.getOrDefault(emptySet())

    val bound: Boolean get() = svc != null

    /** 落位：控件规则按应用切分，域名规则全存一份。 */
    fun land(byPkg: Map<String, List<String>>, domains: List<String>) {
        val s = svc ?: run {
            Log.w("Wurao", "land skipped: XposedService not bound")
            return
        }
        runCatching {
            byPkg.forEach { (pkg, targets) ->
                s.getRemotePreferences("wurao_rules_$pkg")
                    .edit()
                    .putString("rules", targets.joinToString("\n") { b64(it) })
                    .commit()
            }
            s.getRemotePreferences("wurao_domains")
                .edit()
                .putString("domains", domains.joinToString("\n"))
                .commit()
            Log.i("Wurao", "landed ${byPkg.size} apps, ${domains.size} domains")
        }.onFailure { Log.w("Wurao", "land failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
