package com.wurao.core

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中记录的不变量。核心是你要的那条：**同一条规则反复命中不刷屏**，
 * 但每一行都必须能反查到规则（否则排错时知道"被拦了"却不知道该停哪条）。
 */
class HitLogTest {

    private fun rule(id: String, target: String = "[text=\"跳过\"]", note: String = "开屏跳过") = Rule(
        domain = Domain.NODE,
        target = target,
        act = Act.CLICK,
        scope = Scope.Global,
        origin = Origin.Builtin,
        groupKey = id,
        ruleKey = "0",
        note = note,
    )

    @Test
    fun 同一条规则反复命中只占一行_只涨次数() {
        HitLog.clearAll()
        val r = rule("a")
        repeat(10) { i -> HitLog.record(r, "com.x", "Act", nowMs = 1_000L + i) }
        val hits = HitLog.forApp("com.x")
        assertEquals("只一行", 1, hits.size)
        assertEquals(10, hits[0].count)
        assertEquals(1_009L, hits[0].lastAtMs)
    }

    @Test
    fun 不同规则各占一行() {
        HitLog.clearAll()
        HitLog.record(rule("a"), "com.x")
        HitLog.record(rule("b", note = "信息流广告"), "com.x")
        assertEquals(2, HitLog.forApp("com.x").size)
    }

    @Test
    fun 记录里带着规则id_能从记录跳到规则去停用() {
        HitLog.clearAll()
        val r = rule("a")
        HitLog.record(r, "com.x", "com.x.MainActivity")
        val hit = HitLog.forApp("com.x").first()
        assertEquals(r.id, hit.ruleId)
        // 全局规则的作用域段是 `*`：来源:作用域:组:序号
        assertEquals("b:*:a:0", hit.ruleId)
    }

    @Test
    fun 记录里有一句人话和做了什么_排错时看得懂() {
        HitLog.clearAll()
        HitLog.record(rule("a", note = "开屏广告的跳过按钮"), "com.x", "com.x.SplashActivity")
        val d = HitLog.forApp("com.x").first().describe()
        assertTrue("要能看出干了什么：$d", d.contains("开屏广告的跳过按钮"))
        assertTrue("要能看出动作：$d", d.contains(Act.CLICK.key))
        assertTrue("要能看出页面：$d", d.contains("SplashActivity"))
    }

    @Test
    fun 按最近命中时间倒序() {
        HitLog.clearAll()
        HitLog.record(rule("a"), "com.x", nowMs = 1_000)
        HitLog.record(rule("b"), "com.x", nowMs = 5_000)
        HitLog.record(rule("c"), "com.x", nowMs = 3_000)
        val ids = HitLog.forApp("com.x").map { it.ruleId }
        assertEquals(listOf("b:*:b:0", "b:*:c:0", "b:*:a:0"), ids)
    }

    @Test
    fun 不同应用互不干扰() {
        HitLog.clearAll()
        HitLog.record(rule("a"), "com.x")
        HitLog.record(rule("a"), "com.y")
        assertEquals(1, HitLog.forApp("com.x").size)
        assertEquals(1, HitLog.forApp("com.y").size)
    }

    @Test
    fun 域名拦截这类没有规则身份的也能记() {
        HitLog.clearAll()
        HitLog.recordRaw("com.x", "host:ads.example.com", "不让广告联网", Act.BLOCK, Domain.HOST, "ads.example.com")
        val hit = HitLog.forApp("com.x").first()
        assertEquals(Domain.HOST, hit.domain)
        assertTrue(hit.target.contains("ads.example.com"))
    }

    @Test
    fun 有上限_不会无限增长() {
        HitLog.clearAll()
        repeat(150) { i -> HitLog.record(rule("r$i"), "com.x") }
        assertTrue("按应用封顶 100 条", HitLog.forApp("com.x").size <= 100)
    }

    @Test
    fun 清空某个应用不影响别的应用() {
        HitLog.clearAll()
        HitLog.record(rule("a"), "com.x")
        HitLog.record(rule("a"), "com.y")
        HitLog.clear("com.x")
        assertEquals(0, HitLog.forApp("com.x").size)
        assertEquals(1, HitLog.forApp("com.y").size)
    }

    @Test
    fun 最近流水有上限但保留时序() {
        HitLog.clearAll()
        repeat(40) { i -> HitLog.record(rule("r$i"), "com.x", nowMs = i.toLong()) }
        val flow = HitLog.recentFlow()
        assertTrue("流水上限 30", flow.size <= 30)
        assertTrue("最近的最靠前", flow.first().ruleId.contains("r39"))
    }
}
