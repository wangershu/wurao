package com.wurao.core

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Timing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节奏控制不变量 —— 这是耗电约束的落地点，不能靠"应该没问题"。
 */
class PacingTest {

    private fun rule(
        id: String = "r1",
        cooldown: Long = 1_000L,
        max: Int? = 2,
        window: Long? = 10_000L,
    ) = Rule(
        domain = Domain.NODE,
        target = "[text=\"跳过\"]",
        act = Act.CLICK,
        origin = Origin.Builtin,
        groupKey = id,
        ruleKey = "0",
        timing = Timing(cooldownMs = cooldown, maxActions = max, matchWindowMs = window),
    )

    @Test
    fun 默认允许() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        assertTrue(p.allow(rule(), 100))
    }

    @Test
    fun 冷却期内不允许_过了冷却才允许() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        val r = rule(cooldown = 1_000)
        p.record(r, 1_000)
        assertFalse("刚动过手，冷却中", p.allow(r, 1_500))
        assertTrue("过了冷却", p.allow(r, 2_100))
    }

    @Test
    fun 次数上限到了就不再动手() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        val r = rule(cooldown = 0, max = 2)
        p.record(r, 10)
        p.record(r, 20)
        assertFalse("一页最多 2 次", p.allow(r, 30))
    }

    @Test
    fun 换页面后次数重置_冷却不重置() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        val r = rule(cooldown = 0, max = 2)
        p.record(r, 10)
        p.record(r, 20)
        assertFalse(p.allow(r, 30))

        p.onPageChanged("Act2", 40)
        assertTrue("换页后次数清零，可以再动手", p.allow(r, 50))
    }

    @Test
    fun 超出匹配窗口就不再动手() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        val r = rule(window = 5_000)
        assertTrue(p.allow(r, 4_000))
        assertFalse("进页面 6 秒了，窗口已过", p.allow(r, 6_000))
    }

    @Test
    fun 全局暂停时一律不动手_包括内置规则() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        p.setPaused(true)
        assertFalse(p.allow(rule(), 100))
    }

    @Test
    fun 规则没写上限就是不限次数() {
        val p = Pacing()
        p.onPageChanged("Act1", 0)
        val r = rule(cooldown = 0, max = null, window = null)
        repeat(10) { i -> p.record(r, i.toLong()) }
        assertTrue(p.allow(r, 100))
    }

    @Test
    fun 默认节奏是十秒窗口一秒冷却一页两次() {
        val t = Pacing.defaultTiming()
        assertTrue(t.matchWindowMs == 10_000L && t.cooldownMs == 1_000L && t.maxActions == 2)
    }
}
