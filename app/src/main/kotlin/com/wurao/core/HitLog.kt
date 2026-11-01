package com.wurao.core

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Rule

/**
 * 命中记录 —— **排错用的**，不是统计用的。
 *
 * 你要的那条：某个功能不正常时，能立刻知道该停用或删掉哪条规则。所以记录必须满足：
 * 1. **一条规则一行**，不按次数刷屏（同一条规则反复命中只更新时间与次数 —— "下次开启前不用重复记"）；
 * 2. 每行都要能**反查到是哪条规则**（存 ruleId，界面上一点就跳过去停用/删除）；
 * 3. 记的是"做了什么"（点了 / 藏了 / 拦了哪个域名），不是"拦截了多少次请求"这种虚数字；
 * 4. 有上限（按应用封顶），避免长驻进程里无限增长 —— 那本身就是耗电与内存问题。
 *
 * 纯 Kotlin，不依赖 Android，可单测。
 */
object HitLog {

    data class Hit(
        val pkg: String,
        /** 规则身份，界面靠它跳到那条规则去停用/删除。 */
        val ruleId: String,
        /** 一句人话：这条规则干了什么。 */
        val note: String,
        val act: Act,
        val domain: Domain,
        /** 规则的 target（选择器 / 域名 / 组件名），排错时要看。 */
        val target: String,
        /** 最后命中的页面。 */
        val page: String?,
        val count: Int,
        val firstAtMs: Long,
        val lastAtMs: Long,
    ) {
        fun describe(): String = buildString {
            append(note.ifBlank { act.key })
            append(" · ")
            append(act.key)
            if (count > 1) append(" ×").append(count)
            page?.let { append(" @ ").append(it.substringAfterLast('.')) }
        }
    }

    /** 每个应用最多留多少条（按规则聚合，实际远小于这个值；封顶是防意外）。 */
    private const val MAX_PER_APP = 100
    private const val MAX_RECENT = 30

    /** pkg → ruleId → Hit */
    private val byApp = LinkedHashMap<String, LinkedHashMap<String, Hit>>()
    /** 最近动作流水（有时序，排错看"刚才发生了什么"）。 */
    private val recent = ArrayDeque<Hit>(MAX_RECENT)

    /**
     * 记一次命中。同一条规则只更新次数与时间，**不新增一行**。
     *
     * @param page 当前页面（Activity 名），可空
     */
    fun record(rule: Rule, pkg: String, page: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val m = byApp.getOrPut(pkg) { LinkedHashMap() }
        val old = m[rule.id]
        val merged = if (old == null) {
            Hit(
                pkg = pkg,
                ruleId = rule.id,
                note = rule.note,
                act = rule.act,
                domain = rule.domain,
                target = rule.target,
                page = page,
                count = 1,
                firstAtMs = nowMs,
                lastAtMs = nowMs,
            )
        } else {
            old.copy(
                count = old.count + 1,
                lastAtMs = nowMs,
                page = page ?: old.page,
            )
        }
        m[rule.id] = merged
        trim(pkg)

        recent.addLast(merged)
        while (recent.size > MAX_RECENT) recent.removeFirst()
    }

    /**
     * 记录一次"没有规则身份"的动作（例如内置逻辑直接拦了一个域名）。
     * 仍然要能排错，所以要求传一个稳定的 key 与描述。
     */
    fun recordRaw(
        pkg: String,
        key: String,
        note: String,
        act: Act,
        domain: Domain,
        target: String,
        page: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val m = byApp.getOrPut(pkg) { LinkedHashMap() }
        val old = m[key]
        m[key] = if (old == null) {
            Hit(pkg, key, note, act, domain, target, page, 1, nowMs, nowMs)
        } else {
            old.copy(count = old.count + 1, lastAtMs = nowMs, page = page ?: old.page)
        }
        trim(pkg)
    }

    /** 某个应用的命中清单（按最近命中时间倒序）—— 排错主视图。 */
    fun forApp(pkg: String): List<Hit> =
        byApp[pkg]?.values?.sortedByDescending { it.lastAtMs } ?: emptyList()

    /** 最近动作流水（跨应用，按时间倒序）。 */
    fun recentFlow(): List<Hit> = recent.reversed()

    fun clear(pkg: String) {
        byApp.remove(pkg)
        recent.removeAll { it.pkg == pkg }
    }

    fun clearAll() {
        byApp.clear()
        recent.clear()
    }

    fun appCount(): Int = byApp.size

    private fun trim(pkg: String) {
        val m = byApp[pkg] ?: return
        while (m.size > MAX_PER_APP) {
            val oldest = m.values.minByOrNull { it.lastAtMs } ?: break
            m.remove(oldest.ruleId)
        }
    }
}
