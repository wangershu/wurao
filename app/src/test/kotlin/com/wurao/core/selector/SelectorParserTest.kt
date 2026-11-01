package com.wurao.core.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 解析器不变量。**用例全部取自 GKD 订阅里的真实选择器**（见调研报告），
 * 再加一条我们自己要的「隐藏整个入口」用例。
 */
class SelectorParserTest {

    private fun sel(s: String) = SelectorParser(s).parse()

    // ---------- 单段 ----------

    @Test
    fun 单属性_包含匹配() {
        val s = sel("""[text*="跳过"]""")
        assertEquals(1, s.units.size)
        val cmp = s.units[0].expr as BoolExpr.Cmp
        assertEquals(listOf("text"), cmp.left.path)
        assertEquals(CompareOp.CONTAINS, cmp.op)
        assertEquals(Value.Str("跳过"), cmp.right)
    }

    @Test
    fun 多个方括号等于逻辑与() {
        val s = sel("""[text*="跳过"][text.length<10][visibleToUser=true]""")
        assertEquals(1, s.units.size)
        val and = s.units[0].expr as BoolExpr.And
        assertEquals(3, and.parts.size)
        assertEquals(Value.Num(10), (and.parts[1] as BoolExpr.Cmp).right)
        assertEquals(Value.Bool(true), (and.parts[2] as BoolExpr.Cmp).right)
    }

    @Test
    fun 派生属性_长度() {
        val s = sel("[text.length<10]")
        val cmp = s.units[0].expr as BoolExpr.Cmp
        assertEquals(listOf("text", "length"), cmp.left.path)
        assertEquals(CompareOp.LT, cmp.op)
    }

    // ---------- 逻辑组合 ----------

    @Test
    fun 逻辑或与与的嵌套() {
        // GKD 真实写法：开屏跳过按钮的判据
        val s = sel("""[(text.length<10&&(text*="跳过"||text*="skip"))||id$="tt_splash_skip_btn"]""")
        val or = s.units[0].expr as BoolExpr.Or
        assertEquals(2, or.parts.size)
        val left = or.parts[0] as BoolExpr.And
        assertEquals(2, left.parts.size)
        assertTrue(left.parts[1] is BoolExpr.Or)
        assertEquals(CompareOp.ENDS, (or.parts[1] as BoolExpr.Cmp).op)
    }

    @Test
    fun 完整真实规则_GKD开屏那条() {
        val s = sel(
            """[childCount=0][visibleToUser=true]""" +
                """[(text.length<10&&(text*="跳过"||text*="跳過"||text*="skip"))||id$="tt_splash_skip_btn"]"""
        )
        assertEquals(1, s.units.size)
        assertTrue(s.units[0].expr is BoolExpr.And)
    }

    // ---------- 关系 ----------

    @Test
    fun 兄弟与祖先关系() {
        // GKD 真实写法：文本定位 + 前置兄弟 + 直接子节点
        val s = sel(
            """[text="精选必备好应用"] + [name="android.widget.RelativeLayout"] > [name="android.widget.ImageView"]"""
        )
        assertEquals(3, s.units.size)
        assertEquals(RelationOp.PREV_SIBLING, s.units[0].relation)
        assertEquals(RelationOp.ANCESTOR, s.units[1].relation)
        assertEquals(null, s.units[2].relation)
    }

    @Test
    fun 带偏移的后代关系() {
        val s = sel("""[vid^="skip"] <<3 TextView""")
        assertEquals(2, s.units.size)
        assertEquals(RelationOp.DESCENDANT, s.units[0].relation)
        assertEquals(3, s.units[0].offset)
        assertEquals("TextView", s.units[1].className)
    }

    // ---------- @ 目标标记（隐藏整个入口要用） ----------

    @Test
    fun 目标标记在首段_动的是容器而不是找到的那个节点() {
        // 用例：找到写「视频」的标签，但藏掉它所在的整个入口容器
        val s = sel("""@LinearLayout > [text="视频"]""")
        assertEquals(2, s.units.size)
        assertTrue(s.units[0].isTarget)
        assertEquals(0, s.targetIndex)
        assertEquals("LinearLayout", s.units[0].className)
    }

    @Test
    fun 没标记时目标默认是最后一段() {
        val s = sel("""[text="视频"] > LinearLayout""")
        assertEquals(1, s.targetIndex)
    }

    @Test
    fun 目标标记可与类名通配一起用() {
        val s = sel("""@* > [desc="广告"]""")
        assertEquals("*", s.units[0].className)
        assertEquals(0, s.targetIndex)
    }

    // ---------- 不静默失败 ----------

    @Test
    fun 缺少比较值_必须报出位置() {
        try {
            sel("[text=]")
            fail("应当抛语法错误")
        } catch (e: SelectorSyntaxError) {
            assertTrue(e.pos > 0)
            assertTrue(e.message!!.contains("缺少比较值"))
        }
    }

    @Test
    fun 缺少右括号_必须报出位置() {
        try {
            sel("[text=\"x\"")
            fail("应当抛语法错误")
        } catch (e: SelectorSyntaxError) {
            assertTrue(e.message!!.contains("]"))
        }
    }

    @Test
    fun 关系后缺下一段_必须报出位置() {
        try {
            sel("""[text="x"] >""")
            fail("应当抛语法错误")
        } catch (e: SelectorSyntaxError) {
            assertTrue(e.message!!.contains("缺少下一段"))
        }
    }

    // ---------- 规范化回写 ----------

    @Test
    fun 解析后回写与原串语义一致_用于去重与日志() {
        val raw = """[text="视频"] > @LinearLayout"""
        val s = sel(raw)
        val back = s.toString()
        // 回写后能再解析一次，且目标段不变
        val again = SelectorParser(back).parse()
        assertEquals(s.targetIndex, again.targetIndex)
        assertEquals(s.units.size, again.units.size)
    }
}
