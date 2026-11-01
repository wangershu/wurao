package com.wurao.core.analyze

import com.wurao.core.analyze.AdComponentFinder.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动分析的不变量。**用真机拉下来的真实组件清单测**（美团 589 个、懂车帝 1070 个）。
 *
 * 这份测试守的是那条结论：静态分析**能做，但只能做候选推荐**，默认不生效。
 */
class AdComponentFinderTest {

    private fun load(name: String): List<Pair<String, String>> {
        val s = requireNotNull(javaClass.getResourceAsStream("/$name")) { "缺少 $name" }
        return s.bufferedReader().readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 2) p[0] to p[1] else null
        }
    }

    private fun meituan() = AdComponentFinder.find(load("components_meituan.txt"))
    private fun dongchedi() = AdComponentFinder.find(load("components_dongchedi.txt"))

    // ---------------- 真实数据：规模要可控 ----------------

    @Test
    fun 美团候选数量可控_不能几百条() {
        val all = meituan()
        assertTrue("候选要少而精，实际 ${all.size} 条", all.size in 1..30)
    }

    @Test
    fun 懂车帝候选数量可控_不能几百条() {
        val all = dongchedi()
        assertTrue("实际 ${all.size} 条", all.size in 1..40)
    }

    // ---------------- 真实数据：该挖到的要挖到 ----------------

    @Test
    fun 挖到优量汇的广告展示页() {
        val names = meituan().map { it.name }
        assertTrue("GDT 的广告 Activity 要挖到", names.any { it.startsWith("com.qq.e.ads.") })
    }

    @Test
    fun 挖到贝斯广告与自家广告页() {
        val names = meituan().map { it.name }
        assertTrue(names.any { it.startsWith("com.beizi.ad.") })
        assertTrue("开屏广告页要挖到", names.any { it.contains("SplashAdActivity") })
    }

    @Test
    fun 挖到懂车帝的开屏与全屏广告页() {
        val names = dongchedi().map { it.name }
        assertTrue(names.any { it.contains("SplashAdActivity") })
        assertTrue(names.any { it.contains("AdFullscreenActivity") })
    }

    // ---------------- 误伤必须排除 ----------------

    @Test
    fun SDK基础设施被单独标出来_不混进可拦清单() {
        val m = meituan()
        val provider = m.firstOrNull { it.name == "com.qq.e.comm.GDTFileProvider" }
        assertEquals("SDK 的文件提供者属于基础设施", Level.INFRA, provider?.level)
        assertTrue(provider?.reason?.contains("不是广告页") == true)
    }

    @Test
    fun 通知横幅不是广告() {
        val names = dongchedi().map { it.name }
        assertTrue(
            "通知里的 Banner 不该被当成广告",
            names.none { it.contains(".notification.") }
        )
    }

    @Test
    fun 聊天页面不是广告_踩过这个坑() {
        // 早期拿 com.bytedance 当 SDK 特征，懂车帝 200 多个聊天页面全被判成广告
        val names = dongchedi().map { it.name }
        assertTrue("IM 聊天页不该出现", names.none { it.contains(".im.auto.chat.") })
    }

    @Test
    fun 噪声词不算广告() {
        for (n in listOf("com.x.AdapterActivity", "com.x.HeaderActivity", "com.x.LoadingActivity")) {
            val r = AdComponentFinder.find(listOf("activity" to n))
            assertTrue("$n 不该被判成广告", r.isEmpty())
        }
    }

    @Test
    fun 单独的Splash不算广告_启动页不是广告页() {
        assertTrue(AdComponentFinder.find(listOf("activity" to "com.x.SplashActivity")).isEmpty())
    }

    @Test
    fun 带上Ad才是广告相关的() {
        val r = AdComponentFinder.find(listOf("activity" to "com.x.SplashAdActivity"))
        assertEquals(1, r.size)
        assertEquals(Level.AD, r[0].level)
    }

    // ---------------- 候选要能解释自己 ----------------

    @Test
    fun 每条候选都带理由_不让你盲选() {
        val all = meituan() + dongchedi()
        assertTrue(all.isNotEmpty())
        all.forEach { c ->
            assertTrue("候选必须给理由：${c.name}", c.reason.isNotBlank())
        }
    }

    @Test
    fun 驼峰拆分正确() {
        assertEquals(listOf("Splash", "Ad", "Activity"), AdComponentFinder.splitCamel("SplashAdActivity"))
        assertEquals(listOf("AD", "Activity"), AdComponentFinder.splitCamel("ADActivity"))
    }
}
