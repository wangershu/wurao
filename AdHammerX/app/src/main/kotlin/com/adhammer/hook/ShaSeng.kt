package com.adhammer.hook

import com.adhammer.rules.StatsStore
import com.adhammer.util.HLog
import io.github.libxposed.api.XposedInterface
import java.net.InetAddress

/**
 * 沙僧：DNS / Host 拦截。
 *
 * 原版沙僧是靠开 VPN 代理实现域名拦截（耗电、拖慢网速）；
 * 这里直接在进程内 hook InetAddress，命中规则的域名返回 127.0.0.1，
 * 请求在本地就失败，不需要 VPN，开销几乎为零。
 *
 * 规则来自订阅的 sha_seng_hosts.txt / system_hosts.txt，
 * 匹配方式是「相等或后缀匹配」（域名整体或子域）。
 */
object ShaSeng {

    fun install(base: XposedInterface, pkg: String, cl: ClassLoader) {
        // 全局订阅域名 + 这个应用自己加的域名
        val hosts = RuntimeCfg.hostsFor(pkg)
        if (hosts.isEmpty()) return

        val blocked: (String) -> Boolean = { host ->
            val h = host.trim().lowercase()
            hosts.any { rule ->
                val r = rule.trim().lowercase()
                h == r || h.endsWith(".$r")
            }
        }

        val loopback = runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull()

        base.hookIt(
            Reflect.findMethod(InetAddress::class.java, "getByName", String::class.java),
            "shaSeng_getByName"
        ) { chain ->
            val host = chain.getArg(0) as? String
            if (host != null && blocked(host)) {
                HLog.d("block host: $host")
                HitLog.hit(pkg, host, "DNS拦截", StatsStore.KIND_HOST)
                return@hookIt loopback
            }
            chain.proceed()
        }

        base.hookIt(
            Reflect.findMethod(InetAddress::class.java, "getAllByName", String::class.java),
            "shaSeng_getAllByName"
        ) { chain ->
            val host = chain.getArg(0) as? String
            if (host != null && blocked(host) && loopback != null) {
                HLog.d("block host(all): $host")
                return@hookIt arrayOf(loopback)
            }
            chain.proceed()
        }

        HLog.i("ShaSeng installed with ${hosts.size} hosts")
    }

    /** 复用 WuKong 的 hook 封装（同模块内可见） */
    private fun XposedInterface.hookIt(method: java.lang.reflect.Method?, id: String,
                                       hooker: (XposedInterface.Chain) -> Any?) {
        if (method == null) {
            HLog.w("hook target missing: $id")
            return
        }
        runCatching {
            hook(method)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> hooker(chain) }
        }.onFailure { HLog.w("hook failed $id: ${it.message}") }
    }
}
