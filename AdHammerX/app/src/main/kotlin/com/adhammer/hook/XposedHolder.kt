package com.adhammer.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 全局持有 XposedInterface。
 *
 * API 102 起模块不能再调用 legacy 包（XposedHelpers / XposedBridge 全部不可用），
 * 所有 hook 与日志都必须经由 [XposedModule] 自身。本类保存入口实例，
 * 供非入口类（各 Hooker、规则引擎）使用。
 */
object XposedHolder {

    @Volatile
    var base: XposedInterface? = null
        private set

    @Volatile
    var module: AdHammerModule? = null
        private set

    fun attach(m: AdHammerModule) {
        module = m
        base = m
    }

    val isAttached: Boolean get() = base != null
}
