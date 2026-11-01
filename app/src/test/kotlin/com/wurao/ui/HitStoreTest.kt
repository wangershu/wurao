package com.wurao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中记录存储的不变量：聚合（不刷屏）与持久化（重启界面还在）。
 */
class HitStoreTest {

    private fun row(
        pkg: String = "com.x",
        id: String = "r1",
        count: Int = 1,
        last: Long = 100,
        note: String = "开屏跳过",
        act: String = "click",
    ) = HitStore.Row(
        pkg = pkg, ruleId = id, note = note, act = act,
        target = "[text=\"跳过\"]", page = "com.x.Splash", count = count, lastAtMs = last,
    )

    @Test
    fun 同一条规则合并_只涨次数不新增行() {
        HitStore.clear()
        HitStore.merge(row(count = 1, last = 100))
        HitStore.merge(row(count = 1, last = 200))
        val all = HitStore.all()
        assertEquals(1, all.size)
        assertEquals(2, all[0].count)
        assertEquals(200L, all[0].lastAtMs)
    }

    @Test
    fun 不同规则各占一行() {
        HitStore.clear()
        HitStore.merge(row(id = "r1"))
        HitStore.merge(row(id = "r2"))
        assertEquals(2, HitStore.all().size)
    }

    @Test
    fun 按最近时间倒序() {
        HitStore.clear()
        HitStore.merge(row(id = "a", last = 100))
        HitStore.merge(row(id = "b", last = 900))
        HitStore.merge(row(id = "c", last = 500))
        assertEquals(listOf("b", "c", "a"), HitStore.all().map { it.ruleId })
    }

    @Test
    fun 按应用筛选() {
        HitStore.clear()
        HitStore.merge(row(pkg = "com.x", id = "a"))
        HitStore.merge(row(pkg = "com.y", id = "b"))
        assertEquals(1, HitStore.forApp("com.x").size)
    }

    @Test
    fun 编解码往返一致() {
        HitStore.clear()
        val rows = listOf(row(id = "a"), row(pkg = "com.y", id = "b", count = 3))
        val back = HitStore.decode(HitStore.encode(rows))
        assertEquals(rows.size, back.size)
        assertEquals(rows.first().ruleId, back.first().ruleId)
        assertEquals(3, back.last().count)
    }

    @Test
    fun 特殊字符不会破坏行格式() {
        // 目标里可能带制表符/换行（比如选择器很长），必须被抹平，否则解析错位
        val r = row().copy(target = "a\tb\nc")
        val back = HitStore.decode(HitStore.encode(listOf(r)))
        assertEquals(1, back.size)
        assertEquals("a b c", back[0].target)
    }

    @Test
    fun 坏行被跳过而不是整体失败() {
        val back = HitStore.decode("这是一行废话\n" + HitStore.encode(listOf(row())))
        assertEquals(1, back.size)
    }

    @Test
    fun 清空可按应用也可全清() {
        HitStore.clear()
        HitStore.merge(row(pkg = "com.x"))
        HitStore.merge(row(pkg = "com.y"))
        HitStore.clear("com.x")
        assertEquals(0, HitStore.forApp("com.x").size)
        assertEquals(1, HitStore.forApp("com.y").size)
        HitStore.clear()
        assertTrue(HitStore.all().isEmpty())
    }

    @Test
    fun 描述里能看出做了什么和在哪() {
        val d = row(note = "开屏广告的跳过按钮", act = "click", count = 3).describe()
        assertTrue(d.contains("开屏广告的跳过按钮"))
        assertTrue(d.contains("click"))
        assertTrue(d.contains("×3"))
        assertTrue(d.contains("Splash"))
    }
}
