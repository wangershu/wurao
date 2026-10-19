package com.adhammer.util

/**
 * 日志：优先走 LSPosed 模块日志（API 102 的 log(priority, tag, msg)），
 * 不可用时退回 logcat，保证 UI 进程与 hook 进程都能看到。
 */
object HLog {

    private const val TAG = "AdHammer"

    @Volatile
    var bridge: ((Int, String, String) -> Unit)? = null

    fun d(msg: String) = log('D', msg)
    fun i(msg: String) = log('I', msg)
    fun w(msg: String) = log('W', msg)
    fun e(msg: String, t: Throwable? = null) =
        log('E', msg + (t?.let { " : ${it.message}" } ?: ""))

    private fun log(level: Char, msg: String) {
        val priority = when (level) {
            'D' -> android.util.Log.DEBUG
            'W' -> android.util.Log.WARN
            'E' -> android.util.Log.ERROR
            else -> android.util.Log.INFO
        }
        // 写 LSPosed 模块日志
        runCatching { bridge?.invoke(priority, TAG, msg) }
        // 同时写 logcat，方便 adb 直接看
        android.util.Log.println(priority, TAG, msg)
    }
}
