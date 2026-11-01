package com.wurao.inapp

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * hook 装配的安全封装。
 *
 * 三条硬规矩（都对应需求里的「崩溃底线」）：
 * 1. **PROTECTIVE 模式**：回调里抛异常不会传给宿主 App —— 模块出错最多是这次没生效，
 *    绝不能把目标 App 带崩；
 * 2. 找不到方法、装不上 hook 都只是跳过并记一笔，**不静默**（有 tag 可查）；
 * 3. 回调体内的业务逻辑一律再包一层 runCatching，异常不外溢。
 */
internal object HookKit {

    /** 装不上就算了的 hook。返回是否装上。 */
    fun XposedInterface.hookSafe(
        method: Method?,
        id: String,
        body: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        if (method == null) {
            Log.w("hook 跳过（找不到方法）: $id")
            return false
        }
        return runCatching {
            hook(method)
                .setId(id)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> body(chain) }
            true
        }.getOrDefault(false).also { ok ->
            if (!ok) Log.w("装 hook 失败: $id")
        }
    }

    /** 找方法，找不到返回 null（"这个 App 没有这个方法"是正常情况，不是错误）。 */
    fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method? = runCatching {
        cls.getDeclaredMethod(name, *params)
    }.getOrNull() ?: runCatching {
        // getDeclaredMethod 找不到时再试一次含继承的
        cls.getMethod(name, *params)
    }.getOrNull()
}
