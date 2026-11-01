package com.wurao.inapp

/**
 * 日志桥。hook 进程里没有 android.util.Log 的便捷通道，
 * 统一走 LSPosed 的 log()，tag 固定 `Wurao` —— 排障时按 tag 就能把目标进程的命中捞出来。
 */
internal object Log {

    private const val TAG = "Wurao"

    private var bridge: ((Int, String, String) -> Unit)? = null

    fun attach(b: (Int, String, String) -> Unit) {
        bridge = b
    }

    fun i(msg: String) = bridge?.invoke(4, TAG, msg)   // INFO
    fun w(msg: String) = bridge?.invoke(5, TAG, msg)   // WARN
    fun e(msg: String, t: Throwable? = null) =
        bridge?.invoke(6, TAG, if (t == null) msg else "$msg :: ${t.javaClass.simpleName}: ${t.message}")

    fun d(msg: String) = bridge?.invoke(3, TAG, msg)   // DEBUG
}
