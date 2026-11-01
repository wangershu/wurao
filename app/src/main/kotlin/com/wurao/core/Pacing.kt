package com.wurao.core

import com.wurao.core.model.Rule
import com.wurao.core.model.Timing

/**
 * 动作节奏控制：匹配窗口、冷却、次数上限。
 *
 * **这是耗电约束真正的执行点。** 没有它，界面每变一次就跑一次全树匹配，
 * 刷列表时每秒几十次 —— 电就是这么没的（见 `docs/plan.md` 第八节）。
 *
 * 纯 Kotlin，不依赖 Android，所以能单测；进程内执行器只负责在页面切换/动作后调它。
 */
class Pacing {

    private val lastActionAt = HashMap<String, Long>()
    private val actionCount = HashMap<String, Int>()
    private var pageKey: String = ""
    private var pageEnterAt: Long = 0L
    private var paused: Boolean = false

    /** 全局暂停（一键全停用）。 */
    fun setPaused(v: Boolean) {
        paused = v
    }

    val isPaused: Boolean get() = paused

    /** 页面切换：重置本页计数（对应 Timing.resetBy = ACTIVITY）。 */
    fun onPageChanged(key: String, nowMs: Long) {
        if (pageKey == key) return
        pageKey = key
        pageEnterAt = nowMs
        actionCount.clear()
    }

    /** 进页面后只匹配这么久（规则没写就是不限）。 */
    fun inMatchWindow(rule: Rule, nowMs: Long): Boolean {
        val w = rule.timing.matchWindowMs ?: return true
        return nowMs - pageEnterAt <= w
    }

    /** 距上次动作是否过了冷却。 */
    fun cooledDown(rule: Rule, nowMs: Long): Boolean {
        val last = lastActionAt[rule.id] ?: return true
        return nowMs - last >= rule.timing.cooldownMs
    }

    /** 本页动作次数是否还没到上限。 */
    fun underLimit(rule: Rule): Boolean {
        val max = rule.timing.maxActions ?: return true
        return (actionCount[rule.id] ?: 0) < max
    }

    /** 是否允许这一条现在动手。 */
    fun allow(rule: Rule, nowMs: Long): Boolean =
        !paused && inMatchWindow(rule, nowMs) && cooledDown(rule, nowMs) && underLimit(rule)

    /** 动作真的执行了才记账 —— 没点成功的不能算进次数。 */
    fun record(rule: Rule, nowMs: Long) {
        lastActionAt[rule.id] = nowMs
        actionCount[rule.id] = (actionCount[rule.id] ?: 0) + 1
    }

    /** 当前页面 key，排障用。 */
    val currentPage: String get() = pageKey

    companion object {
        /** 内置默认节奏（规则没写时用这个）：10 秒窗口、1 秒冷却、一页最多 2 次。 */
        fun defaultTiming(): Timing = Timing(
            cooldownMs = 1_000L,
            maxActions = 2,
            matchWindowMs = 10_000L,
        )
    }
}
