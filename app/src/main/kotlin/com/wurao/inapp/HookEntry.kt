package com.wurao.inapp

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
// 扩展函数即使同包也要显式 import，否则解析不到
import com.wurao.inapp.HookKit.hookSafe

/**
 * 模块入口（LSPosed / libxposed API 102）。
 *
 * 声明在 `resources/META-INF/xposed/java_init.list`，不再用 assets/xposed_init。
 * 与老 API 的关键差异：没有 XposedHelpers / XposedBridge，hook 走 [XposedInterface.hook]。
 *
 * **触发点只挑低频的**（耗电约束，见 plan 第八节）：
 * 只 hook `Activity.onResume`，然后在页面里做两次延迟匹配，覆盖异步填充的广告。
 * 刻意不 hook `TextView.setText` / `View.draw` —— 那些在刷列表时每秒几十上百次，
 * 是耗电的头号杀手。真需要时再单独评估并加冷却。
 */
class HookEntry : XposedModule() {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.attach { priority, tag, msg -> log(priority, tag, msg) }
        Log.i("无扰已加载: framework=${getFrameworkName()} ver=${getFrameworkVersion()} api=${getApiVersion()}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        // 只在主包装配，避免多进程重复 hook（开销要乘进程数）
        if (!param.isFirstPackage) return
        if (pkg == SELF_PKG) return
        if (!isTarget(pkg)) return

        // 页面出现：低频，一次页面切换才一次
        val m = HookKit.findMethod(Activity::class.java, "onResume")
        hookSafe(m, "wurao_onResume") { chain ->
            val result = chain.proceed()
            runCatching {
                val act = chain.getThisObject() as? Activity ?: return@runCatching
                Executor.onPageChanged(act.javaClass.name)
                val root = runCatching { act.window?.decorView }.getOrNull() ?: return@runCatching
                // 广告常比页面晚一点才填充完，所以补两次延迟；
                // 具体会不会重复点由 Pacing 的冷却与次数上限兜着
                mainHandler.postDelayed({ runCatching { Executor.tick(root, pkg) } }, FIRST_DELAY_MS)
                mainHandler.postDelayed({ runCatching { Executor.tick(root, pkg) } }, SECOND_DELAY_MS)
            }.onFailure { Log.w("onResume 回调出错: ${it.message}") }
            result
        }
        Log.i("已装配: $pkg")
    }

    /** 排除自己和系统桌面/芯片相关包（原版就这么干，避免无谓开销）。 */
    private fun isTarget(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        for (prefix in SKIP_PREFIX) if (pkg.startsWith(prefix)) return false
        return true
    }

    private companion object {
        const val SELF_PKG = "com.wurao.app"
        const val FIRST_DELAY_MS = 300L
        const val SECOND_DELAY_MS = 1_200L

        val SKIP_PREFIX = arrayOf(
            "com.android.systemui",
            "com.miui.home",
            "com.android.launcher",
            "com.qualcomm",
            "com.mediatek",
            "com.android.providers",
            "com.android.phone",
        )
    }
}
