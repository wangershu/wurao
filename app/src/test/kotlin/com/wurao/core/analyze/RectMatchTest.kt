package com.wurao.core.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RectMatchTest {

    @Test
    fun 完全重合得1() {
        val box = RectMatch.parse("240,2413,480,2560")!!
        assertEquals(1f, RectMatch.iou(box, box), 0.001f)
    }

    @Test
    fun 选中整块tab而不是图标或整条底栏() {
        val box = RectMatch.parse("240,2413,480,2560")!!
        val tab = RectMatch.of(240, 2413, 480, 2560)
        val icon = RectMatch.of(300, 2430, 348, 2478)
        val wholeBar = RectMatch.of(0, 2413, 1200, 2560)
        val candidates = listOf(icon, wholeBar, tab).maxBy { RectMatch.iou(it, box) }
        assertTrue("应该选 tab 容器", candidates.contentEquals(tab))
        assertTrue("整条底栏 IoU 要明显低", RectMatch.iou(wholeBar, box) < 0.55f)
        assertTrue("图标 IoU 要明显低", RectMatch.iou(icon, box) < 0.55f)
    }

    @Test
    fun 非法矩形返回null() {
        assertEquals(null, RectMatch.parse("1,2,3"))
        assertEquals(null, RectMatch.parse("10,10,5,20"))
    }
}
