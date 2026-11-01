package com.wurao.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一规则模型的不变量。
 *
 * 这些断言是形态 B 的纪律落地点：五个域共用**一个** Rule 结构，
 * 动作只有 7 个，非法组合必须被报出来（不允许静默失败）。
 */
class RuleTest {

    private fun rule(
        domain: Domain,
        act: Act,
        target: String = "com.x.ad.SplashActivity",
        scope: Scope = Scope.Global,
        origin: Origin = Origin.Builtin,
        groupKey: String = "0",
        ruleKey: String = "0",
        gotoTarget: String? = null,
    ) = Rule(
        domain = domain,
        target = target,
        act = act,
        scope = scope,
        origin = origin,
        groupKey = groupKey,
        ruleKey = ruleKey,
        gotoTarget = gotoTarget,
    )

    // ---------- id 与幂等 ----------

    @Test
    fun id_由来源与作用域共同决定_相同输入得到相同id() {
        val a = rule(Domain.COMPONENT, Act.BLOCK, scope = Scope.App("com.x"), origin = Origin.User)
        val b = rule(Domain.COMPONENT, Act.BLOCK, scope = Scope.App("com.x"), origin = Origin.User)
        assertEquals(a.id, b.id)
        assertEquals("u:com.x:0:0", a.id)
    }

    @Test
    fun id_作用域不同则不同_重复导入不会互相覆盖() {
        val global = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]")
        val perApp = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]", scope = Scope.App("com.x"))
        assertEquals("b:*:0:0", global.id)
        assertEquals("b:com.x:0:0", perApp.id)
    }

    @Test
    fun id_订阅带订阅号_两个订阅的同名规则不冲突() {
        val s1 = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]", origin = Origin.Subscription(1))
        val s2 = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]", origin = Origin.Subscription(2))
        assertEquals("s1:*:0:0", s1.id)
        assertEquals("s2:*:0:0", s2.id)
    }

    // ---------- 动作 × 域 矩阵 ----------

    @Test
    fun 合法组合_全部通过校验() {
        assertTrue(rule(Domain.COMPONENT, Act.BLOCK).validate().isEmpty())
        assertTrue(rule(Domain.COMPONENT, Act.GOTO, gotoTarget = "com.x.MainActivity").validate().isEmpty())
        assertTrue(rule(Domain.SDK, Act.BLOCK, target = "com.x.TTAdSdk#init").validate().isEmpty())
        assertTrue(rule(Domain.HOST, Act.BLOCK, target = "ads.example.com").validate().isEmpty())
        assertTrue(rule(Domain.FILE, Act.BLOCK, target = "/sdcard/ad/").validate().isEmpty())
        assertTrue(rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]").validate().isEmpty())
        assertTrue(rule(Domain.NODE, Act.HIDE, target = "[vid=\"ad_container\"]").validate().isEmpty())
    }

    @Test
    fun 非法组合_点击不能用在域名域_必须被报出() {
        val issues = rule(Domain.HOST, Act.CLICK, target = "ads.example.com").validate()
        assertTrue(issues.any { it.field == "act" })
    }

    @Test
    fun 非法组合_隐藏不能用在组件域_必须被报出() {
        val issues = rule(Domain.COMPONENT, Act.HIDE).validate()
        assertTrue(issues.any { it.field == "act" })
    }

    @Test
    fun 非法组合_阻断不能用在控件域() {
        // BLOCK 的语义是「让它不发生」，控件已经渲染出来了，只能隐藏/摘除/点击
        val issues = rule(Domain.NODE, Act.BLOCK, target = "[text=\"跳过\"]").validate()
        assertTrue(issues.any { it.field == "act" })
    }

    @Test
    fun 七个动作全覆盖_数量不许悄悄变多() {
        // 动作收敛到 7 个是架构决定（旧实现 13 个，复杂度摊在枚举上是 bug 来源）
        assertEquals(7, Act.entries.size)
        assertEquals(5, Domain.entries.size)
    }

    // ---------- 不静默失败 ----------

    @Test
    fun target为空_必须报出来() {
        val issues = rule(Domain.COMPONENT, Act.BLOCK, target = "").validate()
        assertTrue(issues.any { it.field == "target" })
    }

    @Test
    fun GOTO缺落地组件_必须报出来() {
        val issues = rule(Domain.COMPONENT, Act.GOTO).validate()
        assertTrue(issues.any { it.field == "gotoTarget" })
    }

    @Test
    fun 非GOTO却带落地组件_必须报出来() {
        val issues = rule(Domain.COMPONENT, Act.BLOCK, gotoTarget = "com.x.MainActivity").validate()
        assertTrue(issues.any { it.field == "gotoTarget" })
    }

    @Test
    fun 时序参数非法_必须报出来() {
        val negative = rule(Domain.NODE, Act.CLICK, target = "x").copy(timing = Timing(cooldownMs = -1))
        assertTrue(negative.validate().any { it.field == "timing.cooldownMs" })

        val zeroMax = rule(Domain.NODE, Act.CLICK, target = "x").copy(timing = Timing(maxActions = 0))
        assertTrue(zeroMax.validate().any { it.field == "timing.maxActions" })
    }

    // ---------- 生效判定（全局 + 例外名单） ----------

    @Test
    fun 全局规则默认对所有应用生效() {
        val r = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]")
        assertTrue(r.enabledFor("com.any.app"))
    }

    @Test
    fun 例外名单关掉的应用不生效_但规则还在() {
        val r = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]")
        val exclusions = mapOf("com.tencent.mm" to Exclusion("com.tencent.mm", enable = false))
        assertFalse(r.enabledFor("com.tencent.mm", exclusions))
        // 关掉 ≠ 删除：规则本身仍启用，换个应用照样生效
        assertTrue(r.enabled)
        assertTrue(r.enabledFor("com.other"))
    }

    @Test
    fun 应用专属规则只对本包生效() {
        val r = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]", scope = Scope.App("com.x"))
        assertTrue(r.enabledFor("com.x"))
        assertFalse(r.enabledFor("com.y"))
    }

    @Test
    fun 用户关掉的规则不生效_但保留在数据里() {
        val r = rule(Domain.NODE, Act.CLICK, target = "[text=\"跳过\"]", scope = Scope.App("com.x"))
            .copy(enabled = false)
        assertFalse(r.enabledFor("com.x"))
        // 自愈/健康度逻辑只重排定位链，绝不删规则 —— 删了用户就找不回来
        assertTrue(r.validate().isEmpty())
    }
}
