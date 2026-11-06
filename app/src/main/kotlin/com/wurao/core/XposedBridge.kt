package com.wurao.core

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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

    /**
     * 落位：控件规则按应用切分，域名规则全存一份。
     *
     * 走框架的**远程文件**通道，不用 getRemotePreferences：后者在目标进程
     * 是「进程启动那一刻的快照」，App 侧后续写入刷新不过去（实测写侧
     * commit ok=true、hook 侧读到 0 条）。远程文件写完目标进程立刻能读到。
     */
    /** 清空已落位的域名表（规则源页「清空」用）。 */
    fun clearDomains() {
        runCatching {
            svc?.deleteRemoteFile("wurao_domains")
            lastWritten.remove("wurao_domains")
        }
    }

    /** 把有规则的应用推荐进 LSPosed 作用域；框架可能弹确认，结果走回调。 */
    fun requestScope(pkgs: List<String>, onResult: (Boolean, String) -> Unit) {
        val s = svc ?: run { onResult(false, "框架未连接"); return }
        runCatching {
            s.requestScope(pkgs, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(list: List<String>) {
                    onResult(true, "已勾选 ${list.size} 个应用")
                }

                override fun onScopeRequestFailed(reason: String) {
                    onResult(false, reason)
                }
            })
        }.onFailure { onResult(false, it.message ?: "请求失败") }
    }

    fun land(
        byPkg: Map<String, List<String>>,
        domains: List<String>,
        clickByPkg: Map<String, List<String>> = emptyMap(),
    ) {
        val s = svc ?: run {
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
                if (writeRemote(s, key, content)) { lastWritten[key] = content; changed++ }
            }
            // 自动点按规则：按应用单独落位，行格式 b64("act\ttarget")
            clickByPkg.forEach { (pkg, targets) ->
                val key = "wurao_click_$pkg"
                val content = targets.joinToString("\n") { b64(it) }
                if (lastWritten[key] == content) return@forEach
                if (writeRemote(s, key, content)) { lastWritten[key] = content; changed++ }
            }
            val domContent = domains.joinToString("\n")
            if (domContent.isBlank()) {
                // 全部被排除时也要把框架的域名表清掉，否则还在拦
                if (lastWritten.containsKey("wurao_domains")) clearDomains()
            } else if (lastWritten["wurao_domains"] != domContent) {
                if (writeRemote(s, "wurao_domains", domContent)) {
                    lastWritten["wurao_domains"] = domContent
                    changed++
                }
            }
            if (changed > 0)
                Log.i(
                    "Wurao",
                    "landed ${byPkg.size} apps, ${domains.size} domains, " +
                        "${clickByPkg.values.sumOf { it.size }} clicks (changed=$changed)",
                )
        }.onFailure { Log.w("Wurao", "land failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun writeRemote(s: XposedService, name: String, content: String): Boolean = runCatching {
        // 空规则直接删文件：ftruncate 对框架给的 fd 不一定生效，删掉最干净
        if (content.isEmpty()) {
            runCatching { s.deleteRemoteFile(name) }
            return true
        }
        val pfd = s.openRemoteFile(name) ?: return false
        pfd.use {
            val fd = it.fileDescriptor
            // 远程文件是已存在的描述符，新内容更短时必须先截断，否则留残字节
            runCatching { Os.ftruncate(fd, 0L) }
            runCatching { Os.lseek(fd, 0L, OsConstants.SEEK_SET) }
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
        }
        true
    }.getOrDefault(false)

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
