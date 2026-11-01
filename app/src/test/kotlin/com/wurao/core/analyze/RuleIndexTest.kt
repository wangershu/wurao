package com.wurao.core.analyze

import com.wurao.core.selector.SelectorParser
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleIndexTest {

    private fun key(sel: String) = RuleIndex.leafKey(SelectorParser(sel).parse())

    @Test
    fun 精确条件可以当钥匙() {
        assertEquals("v:ad_root", key("[vid=\"ad_root\"]"))
        assertEquals("t:跳过", key("[text=\"跳过\"]"))
        assertEquals("d:广告", key("[desc=\"广告\"]"))
    }

    @Test
    fun 组合条件取能用的那把() {
        assertEquals("v:ad_root", key("[vid=\"ad_root\"][clickable=true]"))
        assertEquals("t:跳过", key("[clickable=true][text=\"跳过\"]"))
    }

    @Test
    fun `祖先链取最后一段的特征`() {
        assertEquals("d:视频", key("[vid=\"bottom_tab_block\"] << [desc=\"视频\"]"))
    }

    @Test
    fun `模糊匹配不能当精确钥匙_否则会静默漏匹配`() {
        // 包含 / 前缀 / 不等 都不是精确值，必须退回"逐个试"的兜底桶
        assertEquals(RuleIndex.KEY_ANY, key("[text*=\"跳过\"]"))
        assertEquals(RuleIndex.KEY_ANY, key("[vid^=\"ad_\"]"))
        assertEquals(RuleIndex.KEY_ANY, key("[text!=\"跳过\"]"))
    }

    @Test
    fun `没有可用特征时退回兜底`() {
        assertEquals(RuleIndex.KEY_ANY, key("[clickable=true]"))
    }
}
