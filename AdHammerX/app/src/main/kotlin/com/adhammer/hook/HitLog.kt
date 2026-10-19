package com.adhammer.hook

import com.adhammer.rules.RuleStore
import com.adhammer.rules.StatsStore
import com.adhammer.rules.model.HitEntry

/**
 * 命中记录：日志（明细）+ 统计（累计计数）一次写两处。
 *
 * 直接写远程偏好（UI 进程读同一份），但做了两道节流：
 * - 内存环形缓冲只留最近 40 条明细
 * - 明细最多 3 秒落盘一次，避免每次命中都走一遍 IPC
 *
 * 计数（[StatsStore]）每次命中都累加 —— 它是统计页的数据源，
 * 漏记一次图表就不准，而且 SharedPreferences 的 apply 本身是异步的，开销可控。
 */
object HitLog {

    private const val MAX = 40
    private const val FLUSH_MS = 3000L

    /**
     * 没有远程偏好时的兜底（八戒在模块自己的进程里跑，拿不到 XposedModule 的 prefs）。
     * 由 `UiState.init` 注入本地偏好。
     */
    var fallback: (() -> android.content.SharedPreferences?)? = null

    private val buf = ArrayList<HitEntry>()
    private var lastFlush = 0L

    private fun target() = RuntimeCfg.prefs() ?: fallback?.invoke()

    @Synchronized
    fun hit(pkg: String, what: String, action: String, kind: String = StatsStore.KIND_PAGE) {
        buf.add(HitEntry(System.currentTimeMillis(), pkg, what, action))
        while (buf.size > MAX) buf.removeAt(0)

        val prefs = target()
        StatsStore.bump(prefs, pkg, kind)

        val now = System.currentTimeMillis()
        if (now - lastFlush >= FLUSH_MS) {
            lastFlush = now
            flushLocked()
        }
    }

    @Synchronized
    private fun flushLocked() {
        val prefs = target() ?: return
        RuleStore.putLog(prefs, ArrayList(buf))
    }
}
