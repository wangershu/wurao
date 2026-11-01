package com.wurao.inapp

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import com.wurao.core.Cfg
import com.wurao.core.RiskGate
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
        // 命中记录要送回界面、读界面写的配置，都需要 Context。
        // 挂 Application.onCreate 而不是 Activity.onCreate —— 开屏广告发生在任何 Activity 之前，
        // 而且配置必须在任何页面出现之前就位。
        HookKit.findMethod(Application::class.java, "onCreate")?.let { mm ->
            hookSafe(mm, "wurao_ctx") { chain ->
                val result = chain.proceed()
                runCatching {
                    val app = chain.getThisObject() as? Application ?: return@runCatching
                    // 命中记录回传需要 Context（写文件那条路）
                    Report.attach(app)
                }.onFailure { Log.w("初始化失败: ${it.message}") }
                result
            }
        }

        // 配置走远程文件，不需要 Context，这里就能读
        runCatching { loadConfig(pkg, param.classLoader ?: javaClass.classLoader) }
            .onFailure { Log.w("读配置失败: ${it.message}") }

        Log.i("已装配: $pkg")
    }

    /**
     * 读界面写的所有配置，并按配置装配 L0。
     *
     * 必须在拿到 Context 之后（Application.onCreate 里）才调 —— 配置走的是
     * ContentProvider，没有 Context 就没法 query。
     *
     * **读失败一律等于"全关 / 不启用"**：一个读错就把风险级能力全开是要出事的，
     * 反过来最多是这次没生效。
     */
    private fun loadConfig(pkg: String, cl: ClassLoader) {
        val cfg = CfgReader.readAll(this)
        // 一键全停：读不到就当作没停（安全的方向是"继续动作"还是"停"？
        // 这里选"没说停就继续"，因为全停是用户主动按的，必须有明确记录）
        Executor.setPaused(cfg[Cfg.KEY_PAUSED] == "true")
        // 统一键优先；没有就退回旧的两份（兼容已经配过规则的手机）
        val rules = Cfg.decodeRules(cfg[Cfg.KEY_RULES]).takeIf { it.isNotEmpty() }
            ?: (Cfg.decodeRules(cfg[Cfg.KEY_USER_RULES]) + Cfg.decodeRules(cfg[Cfg.KEY_SUB_RULES]))
        Executor.setRules(rules)
        Executor.setStopped(Cfg.decodeList(cfg[Cfg.KEY_STOPPED]))

        val kinds = Cfg.decodeKinds(cfg[Cfg.riskKey(pkg)])
        RiskGate.disableAll(pkg)
        kinds.forEach { RiskGate.enable(pkg, it) }

        val extra = Cfg.decodeList(cfg[Cfg.componentsKey(pkg)])
        // 订阅里的域名表（HOST 域规则）并进 DNS 拦截
        val extraHosts = rules.filter { it.domain == com.wurao.core.model.Domain.HOST }
            .map { it.target }
        runCatching { Guards.install(this, pkg, cl, extra, extraHosts) }
            .onFailure { Log.w("L0 装配失败: ${it.message}") }

        Log.i("配置就位[$pkg]: 规则=${rules.size} 风险级=${Cfg.encodeKinds(kinds)} 额外组件=${extra.size} 订阅域名=${extraHosts.size}")
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
