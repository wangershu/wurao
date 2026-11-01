package com.wurao.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSetTest {

    private fun set(vararg hosts: String) = DomainSet.of(hosts.toList())

    @Test
    fun 精确与子域名都命中() {
        val s = set("ads.example.com", "tracker.io")
        assertTrue(s.matches("ads.example.com"))
        assertTrue(s.matches("a.b.ads.example.com"))
        assertTrue(s.matches("tracker.io"))
    }

    @Test
    fun `误伤防线_只匹配域名级前缀`() {
        // notexample.com 只是字符串前缀，不是 example.com 的子域名，不能拦
        val s = set("example.com")
        assertTrue(s.matches("ad.example.com"))
        assertFalse(s.matches("notexample.com"))
        assertFalse(s.matches("example.com.cn"))
        assertFalse(s.matches("api.service.com"))
    }

    @Test
    fun 各种域名表格式都能认() {
        val parsed = DomainSet.parse(
            listOf(
                "# 注释",
                "||ads.example.com^",
                "domain, ad.close.com",
                "0.0.0.0 hosts.ads.com",
                "127.0.0.1 local.ads.com",
                "bare.ads.com",
                "@@exception.ads.com",
            ).joinToString("\n")
        )
        assertTrue(parsed.contains("ads.example.com"))
        assertTrue(parsed.contains("ad.close.com"))
        assertTrue(parsed.contains("hosts.ads.com"))
        assertTrue(parsed.contains("local.ads.com"))
        assertTrue(parsed.contains("bare.ads.com"))
        // 例外规则不支持，必须跳过（不能反过来理解成"要拦"）
        assertFalse(parsed.contains("exception.ads.com"))
    }

    @Test
    fun `大小写与尾点不影响`() {
        val s = set("ADS.Example.com.")
        assertTrue(s.matches("ads.example.com"))
        assertTrue(s.matches("ADS.example.com."))
    }

    @Test
    fun `构建时吃掉被覆盖的条目`() {
        val s = set("example.com", "ad.example.com", "x.ad.example.com")
        assertEquals(1, s.size)
        assertTrue(s.matches("deep.x.ad.example.com"))
    }

    @Test
    fun `空值安全`() {
        assertFalse(DomainSet.EMPTY.matches("anything"))
        assertFalse(set("a.com").matches(null))
        assertFalse(set("a.com").matches(""))
    }
}
