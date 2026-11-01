package com.wurao.core.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **用真机真实界面验证**（`adb shell uiautomator dump` 抓的美团首页，见 test/resources）。
 *
 * 这份数据纠正了两个想当然的写法：
 * 1. 底栏 tab 的文字在 `content-desc` 而不是 `text`（tab 是 `android.view.View`，不是 TextView）
 *    —— 所以按 `[text="视频"]` 写规则根本找不到底栏；
 * 2. 首页内容区**也有**一个 text="视频" 的功能入口 —— 只按文本匹配会误伤它，
 *    必须靠底栏容器的资源 id 限定范围。
 *
 * 这两条就是"隐藏整个入口"这条需求的真实依据。
 */
class RealDumpTest {

    private class XmlAdapter : NodeAdapter<Element> {

        override fun attr(node: Element, path: List<String>): Any? {
            val head = path.firstOrNull() ?: return null
            val v: Any? = when (head) {
                "text" -> node.getAttribute("text").takeIf { it.isNotEmpty() }
                "desc" -> node.getAttribute("content-desc").takeIf { it.isNotEmpty() }
                "id" -> node.getAttribute("resource-id").takeIf { it.isNotEmpty() }
                "vid" -> node.getAttribute("resource-id").substringAfterLast('/').takeIf { it.isNotEmpty() }
                "name" -> className(node)
                "childCount" -> childCount(node)
                "visible", "visibleToUser" -> isVisible(node)
                else -> null
            }
            return if (path.size > 1 && path[1] == "length") (v as? String)?.length else v
        }

        override fun className(node: Element): String? =
            node.getAttribute("class").takeIf { it.isNotEmpty() }

        override fun childCount(node: Element): Int = children(node).size

        override fun child(node: Element, index: Int): Element? = children(node).getOrNull(index)

        override fun parent(node: Element): Element? {
            var p: Node? = node.parentNode
            while (p != null && p !is Element) p = p.parentNode
            return p as? Element
        }

        override fun key(node: Element): Any = System.identityHashCode(node)

        private fun children(node: Element): List<Element> {
            val out = ArrayList<Element>()
            val list = node.childNodes
            for (i in 0 until list.length) {
                val n = list.item(i)
                if (n is Element && n.tagName == "node") out.add(n)
            }
            return out
        }

        /** dump 里没有 visibility 属性，用 bounds 有没有面积代替。 */
        private fun isVisible(node: Element): Boolean {
            val b = node.getAttribute("bounds")
            val m = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""").find(b) ?: return false
            val (x1, y1, x2, y2) = m.destructured
            return x2.toInt() > x1.toInt() && y2.toInt() > y1.toInt()
        }
    }

    private fun loadRoot(): Element {
        val stream = requireNotNull(javaClass.getResourceAsStream("/meituan_home.xml")) {
            "缺少真机 dump 资源"
        }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
    }

    private fun match(sel: String): Element? =
        SelectorMatcher(XmlAdapter()).match(SelectorParser(sel).parse(), loadRoot())

    // ---------------- 真实界面上的定位 ----------------

    @Test
    fun 按desc才能找到底栏视频tab() {
        val v = match("""[desc="视频"]""")
        assertNotNull("底栏 tab 的文字在 content-desc 里", v)
        assertEquals("android.view.View", v?.getAttribute("class"))
    }

    @Test
    fun 按text找视频会误伤首页内容区的入口_不是底栏() {
        val v = match("""[text="视频"]""")
        assertNotNull(v)
        assertEquals("text 命中的是 TextView，不是底栏 tab", "android.widget.TextView", v?.getAttribute("class"))
    }

    @Test
    fun 限定在底栏容器内找_才是准确的写法() {
        // 底栏容器有资源 id，先锁容器再找后代 —— 这才是能长期用的规则
        val v = match("""[vid="bottom_tab_block"] << [desc="视频"]""")
        assertNotNull("底栏容器 + desc 双重限定应该命中", v)
        assertEquals("视频", v?.getAttribute("content-desc"))
    }

    @Test
    fun 底栏容器本身能被定位到() {
        val bar = match("""[vid="bottom_tab_block"]""")
        assertNotNull(bar)
        assertEquals("android.widget.FrameLayout", bar?.getAttribute("class"))
    }

    @Test
    fun 底栏里其它tab也能各自定位_证明可精确区分() {
        for (name in listOf("消息", "购物车", "我的")) {
            val v = match("""[vid="bottom_tab_block"] << [desc="$name"]""")
            assertNotNull("底栏里应该有『$name』", v)
        }
    }

    @Test
    fun 找不到的东西返回空而不是抛异常() {
        assertNull(match("""[vid="bottom_tab_block"] << [desc="不存在的tab"]"""))
    }
}
