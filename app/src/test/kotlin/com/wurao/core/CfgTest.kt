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
 * 配置通道的编解码 —— 界面写、hook 读，两边靠这份文本对齐，错了就会"改了没生效"。
 */
class CfgTest {

    private fun rule(pkg: String, act: Act = Act.HIDE, target: String = "[desc=\"视频\"]", note: String = "藏掉视频") =
        Rule(
            domain = Domain.NODE,
            target = target,
            act = act,
            scope = if (pkg == "*") Scope.Global else Scope.App(pkg),
            origin = Origin.User,
            groupKey = Cfg.GROUP_USER,
            ruleKey = note.hashCode().toString(),
            note = note,
        )

    @Test
    fun 编解码往返一致() {
        val rules = listOf(rule("com.sankuai.meituan"), rule("com.x", Act.CLICK, "[text=\"跳过\"]", "点掉跳过"))
        val back = Cfg.decodeRules(Cfg.encodeRules(rules))
        assertEquals(rules.size, back.size)
        assertEquals(rules[0].target, back[0].target)
        assertEquals(Act.CLICK, back[1].act)
    }

    @Test
    fun 应用专属与全局都能还原() {
        val back = Cfg.decodeRules(Cfg.encodeRules(listOf(rule("com.x"), rule("*"))))
        assertTrue(back[0].scope is Scope.App)
        assertTrue(back[1].scope == Scope.Global)
    }

    @Test
    fun 动作认不出就丢弃_宁可不生效也不能乱点() {
        val text = "com.x\t乱写的动作\t[desc=\"x\"]\t备注\ttrue"
        assertTrue(Cfg.decodeRules(text).isEmpty())
    }

    @Test
    fun 控件域不允许的动作也被丢掉() {
        // BLOCK 是"让它不发生"，控件已经渲染出来了，只能点/藏/摘
        val back = Cfg.decodeRules(Cfg.encodeRules(listOf(rule("com.x"))))
        // encode 时只写合法规则；这里直接喂非法文本验证防御
        assertTrue(Cfg.decodeRules("com.x\tBLOCK\t[desc=\"x\"]\tn\ttrue").isEmpty())
    }

    @Test
    fun 特殊字符被抹平_不会破坏行格式() {
        val r = rule("com.x").copy(target = "a\tb\nc")
        val back = Cfg.decodeRules(Cfg.encodeRules(listOf(r)))
        assertEquals(1, back.size)
        assertEquals("a b c", back[0].target)
    }

    @Test
    fun 空值与坏行安全() {
        assertTrue(Cfg.decodeRules(null).isEmpty())
        assertTrue(Cfg.decodeRules("").isEmpty())
        assertTrue(Cfg.decodeRules("一行废话").isEmpty())
    }

    @Test
    fun 停用状态能被带过去() {
        val r = rule("com.x").copy(enabled = false)
        val back = Cfg.decodeRules(Cfg.encodeRules(listOf(r)))
        assertEquals(false, back[0].enabled)
    }

    @Test
    fun 风险级开关的编码_读不到就是全关() {
        assertTrue(Cfg.decodeKinds(null).isEmpty())
        assertTrue(Cfg.decodeKinds("").isEmpty())
        assertEquals(1, Cfg.decodeKinds("SDK").size)
        assertEquals(2, Cfg.decodeKinds("COMPONENT,NET").size)
        assertTrue("认不出的名字要丢掉", Cfg.decodeKinds("SDK,乱写").size == 1)
    }

    @Test
    fun 组件名单编解码() {
        val list = listOf("com.x.AdActivity", "com.x.AdService")
        assertEquals(list, Cfg.decodeList(Cfg.encodeList(list)))
        assertTrue(Cfg.decodeList(null).isEmpty())
    }
}
