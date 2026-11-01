package com.wurao.core.selector

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 测试用：把 `uiautomator dump` 的 XML 当成控件树喂给选择器引擎。
 *
 * 有了它就能做闭环验证：**真机界面 → 抓节点 → 生成规则 → 用规则再匹配回同一个节点**。
 */
class XmlNodeAdapter : NodeAdapter<Element> {

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

    /** dump 里没有 visibility，用 bounds 有没有面积代替。 */
    private fun isVisible(node: Element): Boolean {
        val m = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
            .find(node.getAttribute("bounds")) ?: return false
        val (x1, y1, x2, y2) = m.destructured
        return x2.toInt() > x1.toInt() && y2.toInt() > y1.toInt()
    }

    companion object {
        /** 读测试资源里的真机 dump。 */
        fun rootOf(name: String): Element {
            val s = requireNotNull(XmlNodeAdapter::class.java.getResourceAsStream("/$name")) {
                "缺少真机 dump 资源：$name"
            }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(s).documentElement
        }

        /** 按条件找第一个节点。 */
        fun find(root: Element, pred: (Element) -> Boolean): Element? =
            root.getElementsByTagName("node").let { list ->
                (0 until list.length).map { list.item(it) as Element }.firstOrNull(pred)
            }
    }
}
