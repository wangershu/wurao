package com.wurao.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMatcherTest {

    private val hosts = setOf("ads.example.com", "example.com", "tracker.io")

    @Test
    fun 精确命中() {
        assertTrue(HostMatcher.matches("ads.example.com", hosts))
    }

    @Test
    fun 后缀命中() {
        assertTrue(HostMatcher.matches("a.b.ads.example.com", hosts))
        assertTrue(HostMatcher.matches("x.example.com", hosts))
    }

    @Test
    fun 不相关的域名不命中() {
        assertFalse(HostMatcher.matches("example.com.cn", hosts))
        assertFalse(HostMatcher.matches("notexample.com", hosts))
        assertFalse(HostMatcher.matches("api.service.com", hosts))
    }

    @Test
    fun 带前缀杂乱字符也能认() {
        assertTrue(HostMatcher.matches("https://ads.example.com/path?x=1", hosts))
        assertTrue(HostMatcher.matches("ads.example.com.", hosts))
        assertTrue(HostMatcher.matches("ADS.example.com", hosts))
    }

    @Test
    fun 空值安全() {
        assertFalse(HostMatcher.matches(null, hosts))
        assertFalse(HostMatcher.matches("", hosts))
        assertFalse(HostMatcher.matches("anything", emptySet()))
    }
}
