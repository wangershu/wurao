package com.wurao.core.analyze

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.selector.NodeAdapter
import com.wurao.core.selector.SelectorMatcher
import com.wurao.core.selector.SelectorParser
import com.wurao.core.selector.XmlNodeAdapter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 用真机 dump 守住一件事：**索引筛选不能把能命中的规则筛掉**。
 *
 * 索引是个优化，优化一旦"优化掉了正确结果"就最难查（不报错、只是没效果），
 * 所以这里用真实的美团首页 dump 做端到端闭环：
 * 建索引 → 遍历树挑候选 → 候选里必须有它 → 而且真的能匹配到节点。
 */
class RuleIndexRealTest {

    private val adapter = XmlNodeAdapter()

    private fun loadRoot(): Element {
        val stream = requireNotNull(javaClass.getResourceAsStream("/meituan_home.xml")) {
            "缺少真机 dump 资源"
        }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
    }

    private val target = """[vid="bottom_tab_block"] << [desc="视频"]"""

    private fun rule(): Rule = Rule(
        domain = Domain.NODE,
        target = target,
        act = Act.HIDE,
        scope = Scope.App("com.sankuai.meituan"),
        origin = Origin.User,
        groupKey = "user",
        ruleKey = "test",
        note = "隐藏视频入口",
    )

    @Test
    fun `索引挑得出能命中的那条规则`() {
        val r = rule()
        val plan = RuleIndex.build(listOf(r))["com.sankuai.meituan"]
        assertNotNull("这个包要有自己的计划", plan)

        val hit = HashSet<String>()
        walk(loadRoot(), adapter) { node ->
            val keys = RuleIndex.keysOf(adapter, node)
            if (keys.isEmpty()) return@walk
            plan!!.forKeys(keys).forEach { hit.add(it.rule.id) }
        }
        assertTrue("能命中的规则必须被挑出来，否则就是静默漏匹配", hit.contains(r.id))
    }

    @Test
    fun `而且这条规则真的能匹配到节点`() {
        val root = loadRoot()
        val sel = SelectorParser(target).parse()
        assertNotNull(SelectorMatcher(adapter).match(sel, root))
    }

    private fun <T> walk(root: T, ad: NodeAdapter<T>, visit: (T) -> Unit) {
        val stack = ArrayList<T>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            visit(node)
            val n = ad.childCount(node)
            for (i in n - 1 downTo 0) ad.child(node, i)?.let(stack::add)
        }
    }
}
