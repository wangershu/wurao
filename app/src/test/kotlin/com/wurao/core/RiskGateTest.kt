package com.wurao.core

import com.wurao.core.RiskGate.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风险级开关的不变量 —— 默认必须全关，这是你提的那条要求（SDK 拦截可能让 App 起不来）。
 */
class RiskGateTest {

    @Test
    fun 默认全部关闭() {
        assertFalse(RiskGate.isEnabled("com.sankuai.meituan", Kind.SDK))
        assertFalse(RiskGate.isEnabled("com.sankuai.meituan", Kind.NET))
        assertFalse(RiskGate.isEnabled("com.sankuai.meituan", Kind.COMPONENT))
        assertFalse(RiskGate.anyEnabled("com.sankuai.meituan"))
    }

    @Test
    fun 按App单独开_不影响别的应用() {
        RiskGate.enable("com.sankuai.meituan", Kind.SDK)
        assertTrue(RiskGate.isEnabled("com.sankuai.meituan", Kind.SDK))
        assertFalse("别的应用不受影响", RiskGate.isEnabled("com.ss.android.auto", Kind.SDK))
    }

    @Test
    fun 同一个应用可以只开其中一项() {
        RiskGate.enable("com.x", Kind.NET)
        assertTrue(RiskGate.isEnabled("com.x", Kind.NET))
        assertFalse(RiskGate.isEnabled("com.x", Kind.SDK))
    }

    @Test
    fun 出问题能一键全退() {
        RiskGate.enable("com.x", Kind.SDK)
        RiskGate.enable("com.x", Kind.NET)
        RiskGate.disableAll("com.x")
        assertFalse("一键退回后都不开", RiskGate.anyEnabled("com.x"))
    }

    @Test
    fun 关掉单项_其它保留() {
        RiskGate.enable("com.y", Kind.SDK)
        RiskGate.enable("com.y", Kind.NET)
        RiskGate.disable("com.y", Kind.SDK)
        assertFalse(RiskGate.isEnabled("com.y", Kind.SDK))
        assertTrue(RiskGate.isEnabled("com.y", Kind.NET))
    }

    @Test
    fun 种子数据来自真机实测_不是编的() {
        assertTrue("美团有实测出来的广告组件", Seed.adComponents.containsKey("com.sankuai.meituan"))
        assertTrue("懂车帝有实测出来的广告组件", Seed.adComponents.containsKey("com.ss.android.auto"))
        assertTrue(Seed.adComponents["com.sankuai.meituan"]!!.any { it.startsWith("com.qq.e.ads.") })
        assertTrue("穿山甲初始化入口要在名单里", Seed.adSdkInits.any { it.first.contains("TTAdSdk") })
    }
}
