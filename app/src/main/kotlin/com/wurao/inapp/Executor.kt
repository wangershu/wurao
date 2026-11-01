package com.wurao.inapp

import android.view.View
import com.wurao.core.Builtin
import com.wurao.core.Pacing
import com.wurao.core.model.Rule
import com.wurao.core.selector.Selector
import com.wurao.core.selector.SelectorMatcher
import com.wurao.core.selector.SelectorParser

/**
 * 进程内执行器：三道闸 + 匹配 + 动作。
 *
 * 三道闸（越便宜越靠前，这是耗电约束的第一道防线）：
 * 1. **进程闸** —— 这个 App 没有要跑的规则，一次匹配都不做；
 * 2. **App 闸** —— 规则对这个包是否启用（例外名单关掉的就不跑）；
 * 3. **节奏闸** —— 冷却 / 次数上限 / 匹配窗口（见 [Pacing]）。
 *
 * 返回命中的规则备注，供日志与排障用；没命中返回 null。
 */
object Executor {

    private val pacing = Pacing()

    /** 选择器解析结果缓存：编译一次反复用，别每帧都解析一遍字符串。 */
    private val selectorCache = HashMap<String, Selector>()

    /** 例外名单：全局规则在这个包上被关掉（S6 由界面写入）。 */
    private val excluded = HashSet<String>()

    fun setPaused(v: Boolean) = pacing.setPaused(v)

    fun exclude(pkg: String) {
        excluded.add(pkg)
    }

    fun include(pkg: String) {
        excluded.remove(pkg)
    }

    fun onPageChanged(pageKey: String) = pacing.onPageChanged(pageKey, System.currentTimeMillis())

    /**
     * 跑一次。必须在主线程调用。
     *
     * @param root 当前页面的根 View
     * @param pkg 当前包名
     * @return 命中的规则备注（null = 没命中或没资格跑）
     */
    fun tick(root: View, pkg: String): String? {
        if (pacing.isPaused) return null

        // ① 进程闸 + ② App 闸：先把没资格的规则筛掉，再谈匹配
        val rules = activeRules(pkg)
        if (rules.isEmpty()) return null

        val adapter = ViewAdapter(pkg)
        val now = System.currentTimeMillis()

        for (rule in rules) {
            if (rule.domain != com.wurao.core.model.Domain.NODE) continue   // ③ 域闸：本阶段只跑控件域
            if (!pacing.allow(rule, now)) continue

            val selector = selectorCache.getOrPut(rule.target) {
                runCatching { SelectorParser(rule.target).parse() }.getOrNull() ?: return null
            }
            val target = runCatching { SelectorMatcher(adapter).match(selector, root) }.getOrNull()
                ?: continue

            val done = runCatching { Actor.perform(rule.act, target) }.getOrDefault(false)
            if (done) {
                pacing.record(rule, now)
                val note = rule.note.ifBlank { rule.act.key }
                // 这条日志由**目标进程**打出 —— 进程内生效的证据就是它（PID 不是模块进程）
                Log.i("命中[$pkg] $note <- ${rule.target}")
                return note
            }
        }
        return null
    }

    /**
     * 当前该跑哪些规则。
     * S2 阶段只有内置规则；S3 接上订阅后，这里换成「内置 + 订阅 + 用户自抓」的合并结果，
     * 顺序按「用户自抓 > 订阅 > 内置」（自定规则是亲眼看到才建的，最可信）。
     */
    private fun activeRules(pkg: String): List<Rule> =
        Builtin.rules().filter { it.enabled && it.enabledFor(pkg) && !excluded.contains(pkg) }

    /** 排障用：当前节奏状态。 */
    fun debugState(): String = "page=${pacing.currentPage} paused=${pacing.isPaused}"
}
