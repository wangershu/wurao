package com.adhammer.hook

import com.adhammer.util.HLog

/**
 * 功能隔离：单项注册失败只降级那一项，不影响其他功能。
 *
 * 目标 App 改版、类被删、方法签名变化都会导致某个 hook 失败，
 * 有了这层保护，其余能力照常工作 —— 这对去广告模块尤其重要，
 * 否则一次改版就会让整个模块「看起来失效」。
 */
object FeatureGuard {

    private val states = LinkedHashMap<String, Boolean>()

    fun runAll(pkg: String, block: Builder.() -> Unit) {
        states.clear()
        val builder = Builder()
        builder.block()
        builder.items.forEach { (name, action) ->
            val ok = runCatching { action() }.isSuccess
            states[name] = ok
            HLog.i("  [$pkg] ${if (ok) "OK     " else "FAILED "} $name")
        }
        val ok = states.values.count { it }
        HLog.i("  [$pkg] 汇总: $ok/${states.size} 项可用")
    }

    class Builder {
        internal val items = LinkedHashMap<String, () -> Unit>()
        fun add(name: String, action: () -> Unit) {
            items[name] = action
        }
    }

    fun snapshot(): Map<String, Boolean> = states.toMap()
}
