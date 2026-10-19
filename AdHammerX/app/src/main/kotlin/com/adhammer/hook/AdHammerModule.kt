package com.adhammer.hook

import com.adhammer.rules.Cfg
import com.adhammer.util.HLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（LSPosed API 102）。
 *
 * 声明在 META-INF/xposed/java_init.list 中，不再使用 assets/xposed_init。
 *
 * 与旧 API 的关键差异：
 * - 入口继承 [XposedModule]，回调是 onModuleLoaded / onPackageReady
 * - 禁止再访问 de.robv.android.xposed.* （XposedHelpers / XposedBridge 均不可用）
 * - hook 走 hook(Executable).intercept { chain -> ... }
 * - 模块 UI 与 hook 进程通过 getRemotePreferences 共享规则
 */
class AdHammerModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedHolder.attach(this)
        HLog.bridge = { priority, tag, msg -> log(priority, tag, msg) }
        HLog.i("AdHammer loaded: framework=${getFrameworkName()} ver=${getFrameworkVersion()} api=${getApiVersion()}")

        // 读取模块 UI 同步下来的规则
        runCatching {
            RuntimeCfg.init(getRemotePreferences(com.adhammer.rules.RuleStore.GROUP))
        }.onFailure { HLog.e("init remote prefs failed", it) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        // 只在第一个包（主包）加载时安装，避免多进程重复 hook
        if (!param.isFirstPackage) return
        if (isSelf(pkg)) return
        if (!isTarget(pkg)) return

        val cl = param.classLoader ?: return
        if (!RuntimeCfg.hasAnyRule(pkg)) {
            HLog.d("no rule for $pkg, skip")
            return
        }

        // 全局暂停：一条 hook 都不装
        if (RuntimeCfg.pause) {
            HLog.d("paused, skip $pkg")
            return
        }

        // 应用级开关：这个 app 被单独关掉了
        if (RuntimeCfg.appOff(pkg)) {
            HLog.d("app disabled: $pkg")
            return
        }

        HLog.i("install hooks: ${RuntimeCfg.summary(pkg)}")
        FeatureGuard.runAll(pkg) {
            if (RuntimeCfg.wuKong && !RuntimeCfg.subOff(pkg, Cfg.SUB_WU_KONG)) {
                add("悟空-Activity") { WuKong.installActivity(this@AdHammerModule, pkg, cl) }
                add("悟空-View") { WuKong.installViews(this@AdHammerModule, pkg, cl) }
                add("悟空-URL") { WuKong.installUrl(this@AdHammerModule, pkg, cl) }
                add("悟空-组件") { WuKong.installComponent(this@AdHammerModule, pkg, cl) }
                add("悟空-文件替换") { WuKong.installReplace(this@AdHammerModule, pkg) }
            }
            if (RuntimeCfg.shaSeng && !RuntimeCfg.subOff(pkg, Cfg.SUB_SHA_SENG)) {
                add("沙僧-DNS") { ShaSeng.install(this@AdHammerModule, pkg, cl) }
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HLog.i("system server starting")
    }

    private fun isSelf(pkg: String) =
        pkg == "com.adhammer.x" || pkg == "me.app.xp.ad.hammer"

    /** 排除系统/桌面/芯片相关，避免无谓 hook */
    private fun isTarget(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val skipPrefix = arrayOf(
            "com.miui.home", "com.qualcomm", "com.mediatek", "com.android.shell",
            "com.android.providers", "com.android.phone", "org.omnirom"
        )
        return skipPrefix.none { pkg.startsWith(it) }
    }

    companion object {
        /** 统一的 hook 优先级与异常模式 */
        fun io.github.libxposed.api.XposedInterface.HookBuilder.defaults(id: String)
                : io.github.libxposed.api.XposedInterface.HookBuilder =
            setId(id).setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
    }
}
