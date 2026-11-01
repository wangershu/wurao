package com.wurao.inapp

import android.app.Activity
import android.content.Intent
import com.wurao.core.RiskGate
import com.wurao.core.Seed
import io.github.libxposed.api.XposedInterface
import java.net.InetAddress
// 扩展函数即使同包也要显式 import
import com.wurao.inapp.HookKit.hookSafe

/**
 * L0「渲染前」三件套：广告还没出来就掐掉。
 *
 * **全部默认关闭**，必须 [RiskGate] 按 App 打开 —— 拦掉 SDK 初始化有可能让 App 自己起不来，
 * 这事得你（用户）决定，不能模块替你决定。
 *
 * 三条共同规矩：
 * 1. 装不上、找不到类/方法都只是跳过并记一笔（"这个 App 没接这个 SDK"是正常情况）；
 * 2. 回调里抛异常不会传给宿主（[HookKit.hookSafe] 用了 PROTECTIVE）；
 * 3. 命中才动手，不命中时开销只是"查一次名单"。
 */
object Guards {

    fun install(base: XposedInterface, pkg: String, cl: ClassLoader) {
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.COMPONENT)) installComponent(base, pkg)
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.NET)) installNet(base, pkg)
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.SDK)) installSdk(base, cl)
    }

    // ---------------- ① 拦广告页面 ----------------

    /**
     * 广告页还没打开就拦掉：在 `startActivity` 阶段把 Intent 掏空。
     *
     * 这是原版 at=9「防火墙拦截」的做法 —— 比"打开后再 finish"好得多：不闪、不崩。
     */
    private fun installComponent(base: XposedInterface, pkg: String) {
        val targets = Seed.adComponents[pkg] ?: return
        if (targets.isEmpty()) return

        val methods = HookKit.findAll(Activity::class.java, "startActivity")
        if (methods.isEmpty()) {
            Log.w("组件拦截：找不到 startActivity")
            return
        }
        methods.forEachIndexed { i, m ->
            base.hookSafe(m, "wurao_block_page_$i") { chain ->
                var blocked = false
                runCatching {
                    for (arg in chain.getArgs()) {
                        if (arg !is Intent) continue
                        val cn = arg.component ?: continue
                        if (cn.className in targets) {
                            // 掏空 Intent：调用方 startActivity 之后不会真正跳转
                            arg.component = null
                            arg.action = null
                            runCatching { arg.extras?.clear() }
                            blocked = true
                            Log.i("拦广告页: ${cn.className}")
                            break
                        }
                    }
                }
                if (blocked) null else chain.proceed()
            }
        }
        Log.i("组件拦截已装配（${targets.size} 个广告组件）")
    }

    // ---------------- ② 不让广告联网 ----------------

    /**
     * DNS 层：广告域名解析到本地，请求出门前就失败。
     *
     * 只 hook `getByName` 这一个入口 —— 这是最便宜的一层（一次解析才一次查询）。
     * 刻意**不** hook Socket 读写层：那会让每次网络读写都变成一次规则匹配（架构里明确不做）。
     */
    private fun installNet(base: XposedInterface, pkg: String) {
        val hosts = Seed.adHosts
        if (hosts.isEmpty()) return
        val m = HookKit.findMethod(InetAddress::class.java, "getByName", String::class.java) ?: run {
            Log.w("DNS 拦截：找不到 getByName")
            return
        }
        base.hookSafe(m, "wurao_block_net") { chain ->
            val host = chain.getArgs().firstOrNull() as? String
            if (host != null && isAdHost(host, hosts)) {
                Log.i("拦域名: $host")
                // 不能再调 getByName（会递归回自己），直接按字节构造
                return@hookSafe runCatching {
                    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                }.getOrNull() ?: chain.proceed()
            }
            chain.proceed()
        }
        Log.i("DNS 拦截已装配（${hosts.size} 个域名）: $pkg")
    }

    /** 后缀匹配：`a.b.example.com` 命中 `example.com`。 */
    private fun isAdHost(host: String, hosts: List<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    // ---------------- ③ 让广告 SDK 起不来 ----------------

    /**
     * 让 SDK 的初始化不发生：后面所有广告 API 都拿不到实例。
     *
     * **这是最抗改版的一招** —— 界面天天变，SDK 的类名几年不变（AdClose 就是靠这个做到零规则）。
     * 代价也正是它属于风险级的原因：SDK 起不来，依赖它的业务逻辑可能跟着瘫。
     */
    private fun installSdk(base: XposedInterface, cl: ClassLoader) {
        var installed = 0
        for ((clsName, methodName) in Seed.adSdkInits) {
            val cls = runCatching { cl.loadClass(clsName) }.getOrNull()
            // 这个 App 没接这个 SDK 是正常情况，不是错误
            if (cls == null) continue
            val methods = HookKit.findAll(cls, methodName)
            if (methods.isEmpty()) continue
            methods.forEachIndexed { i, m ->
                base.hookSafe(m, "wurao_block_sdk_${clsName.hashCode()}_$i") { chain ->
                    Log.i("拦 SDK 初始化: $clsName#$methodName")
                    // 不 proceed = init 从未发生
                    when (m.returnType) {
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                }
            }
            installed++
        }
        if (installed > 0) Log.i("SDK 拦截已装配（$installed 个 SDK）")
    }
}
