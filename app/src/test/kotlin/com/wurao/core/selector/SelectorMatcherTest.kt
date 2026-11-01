package com.wurao.core.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 求值不变量。用一棵假的控件树测（与 Android 无关 —— 这正是 NodeAdapter 抽象的意义：
 * 进程内换成 View 实现同一个接口即可，匹配代码一行不改）。
 */
class SelectorMatcherTest {

    // ---------------- 假节点与适配器 ----------------

    private class TNode(
        val cls: String,
        val attrs: Map<String, Any?> = emptyMap(),
    ) {
        val children = mutableListOf<TNode>()
        var parent: TNode? = null
        fun add(child: TNode): TNode {
            child.parent = this
            children.add(child)
            return this
        }
    }

    private object Adapter : NodeAdapter<TNode> {
        override fun attr(node: TNode, path: List<String>): Any? {
            var cur: Any? = null
            path.forEachIndexed { i, name ->
                cur = if (i == 0) {
                    when (name) {
                        "name" -> node.cls
                        "childCount" -> node.children.size
                        else -> node.attrs[name]
                    }
                } else {
                    when (name) {
                        "length" -> (cur as? String)?.length
                        else -> null
                    }
                }
            }
            return cur
        }

        override fun className(node: TNode): String? = node.cls
        override fun childCount(node: TNode): Int = node.children.size
        override fun child(node: TNode, index: Int): TNode? = node.children.getOrNull(index)
        override fun parent(node: TNode): TNode? = node.parent
        override fun key(node: TNode): Any = System.identityHashCode(node)
    }

    private fun match(sel: String, root: TNode): TNode? =
        SelectorMatcher(Adapter).match(SelectorParser(sel).parse(), root)

    /** 造一棵像样的树：底部标签栏里有「首页/视频/我的」三个入口 */
    private fun tree(): TNode {
        val root = TNode("android.widget.FrameLayout")
        val bar = TNode("android.widget.LinearLayout", mapOf("vid" to "tab_bar"))
        root.add(bar)
        bar.add(TNode("android.widget.TextView", mapOf("text" to "首页")))
        val video = TNode("android.widget.LinearLayout", mapOf("vid" to "tab_video"))
        video.add(TNode("android.widget.TextView", mapOf("text" to "视频")))
        video.add(TNode("android.widget.ImageView"))
        bar.add(video)
        bar.add(TNode("android.widget.TextView", mapOf("text" to "我的")))
        return root
    }

    // ---------------- 基本匹配 ----------------

    @Test
    fun 按文本找到节点() {
        val found = match("""[text="视频"]""", tree())
        assertEquals("视频", found?.attrs?.get("text"))
    }

    @Test
    fun 按资源名找到节点() {
        val found = match("""[vid="tab_video"]""", tree())
        assertEquals("tab_video", found?.attrs?.get("vid"))
    }

    @Test
    fun 找不到的返回空_不抛异常() {
        assertNull(match("""[text="不存在的东西"]""", tree()))
    }

    // ---------------- 隐藏整个入口（@ 目标标记） ----------------

    @Test
    fun 找到标签但动它的容器_这就是隐藏整个入口() {
        // 关系方向按 GKD 源码语义：`A > B` 中 B 是 A 的祖先（从 A 往上找）。
        // 所以「从写『视频』的节点往上找容器，容器才是要藏的目标」要这么写：
        val target = match("""[text="视频"] > @LinearLayout""", tree())
        // 目标是容器本身，而不是那个写着「视频」的 TextView
        assertEquals("android.widget.LinearLayout", target?.cls)
        assertEquals("tab_video", target?.attrs?.get("vid"))
    }

    @Test
    fun 不标记时目标是最后一段_即找到的那个节点本身() {
        val target = match("""[text="视频"] > LinearLayout""", tree())
        assertEquals("android.widget.LinearLayout", target?.cls)
    }

    // ---------------- 派生属性与逻辑 ----------------

    @Test
    fun 文本长度约束() {
        // 深度先序，先遇到「首页」——匹配顺序必须是确定的，否则规则行为不可预测
        assertEquals("首页", match("""[text.length<5]""", tree())?.attrs?.get("text"))
        assertEquals("视频", match("""[text.length<5][vid="tab_video"]""".replace("""[vid="tab_video"]""", """[text="视频"]"""), tree())?.attrs?.get("text"))
        assertNull(match("""[text.length>100]""", tree()))
    }

    @Test
    fun 只要叶子节点_childCount为0() {
        val found = match("""[childCount=0][text="视频"]""", tree())
        assertEquals("android.widget.TextView", found?.cls)
    }

    @Test
    fun 逻辑或_多个候选文案任一命中() {
        val found = match("""[text="视频"||text="首页"]""", tree())
        assertEquals("首页", found?.attrs?.get("text")) // 深度先序先遇到「首页」
    }

    @Test
    fun 前缀与后缀匹配() {
        // tab_bar 在树里更靠前，所以 `^="tab_"` 先命中它；要精确命中视频就用更长的串
        assertEquals("tab_bar", match("""[vid^="tab_"]""", tree())?.attrs?.get("vid"))
        assertEquals("tab_video", match("""[vid^="tab_v"]""", tree())?.attrs?.get("vid"))
        assertEquals("tab_video", match("""[vid$="_video"]""", tree())?.attrs?.get("vid"))
    }

    // ---------------- 关系 ----------------

    @Test
    fun 父子关系_从容器找子节点() {
        val found = match("""[vid="tab_video"] < TextView""", tree())
        assertEquals("视频", found?.attrs?.get("text"))
    }

    @Test
    fun 后代关系带偏移_取第二个后代() {
        val found = match("""[vid="tab_bar"] <<2 TextView""", tree())
        assertEquals("视频", found?.attrs?.get("text"))
    }

    @Test
    fun 兄弟关系_找前一个兄弟() {
        // 「视频」文字节点的父是 video 容器，它的兄弟是「首页」「我的」这两个入口容器
        val found = match("""[vid="tab_video"] + [text="首页"]""", tree())
        assertEquals("首页", found?.attrs?.get("text"))
    }

    @Test
    fun 兄弟关系_找后一个兄弟() {
        val found = match("""[text="首页"] - [vid="tab_video"]""", tree())
        assertEquals("tab_video", found?.attrs?.get("vid"))
    }

    // ---------------- 字符串与边界 ----------------

    @Test
    fun 属性不存在时不崩_按空处理() {
        // ImageView 没有 text，[text="x"] 不应命中它
        assertNull(match("""[text="不存在"]""", tree()))
    }

    @Test
    fun 深树不爆栈_显式栈遍历() {
        // 造一条 2000 层深的链
        var cur = TNode("android.widget.FrameLayout")
        val root = cur
        repeat(2000) {
            val next = TNode("android.widget.FrameLayout")
            cur.add(next)
            cur = next
        }
        cur.attrs.toMutableMap().also { }
        val deep = TNode("android.widget.TextView", mapOf("text" to "底部"))
        cur.add(deep)
        // 能找到，且不抛 StackOverflowError
        assertEquals("底部", match("""[text="底部"]""", root)?.attrs?.get("text"))
    }

    @Test
    fun 目标节点是同一个对象引用_不是拷贝() {
        val root = tree()
        val bar = root.children[0]
        val video = bar.children[1]
        val target = match("""[text="视频"] > @LinearLayout""", root)
        assertSame(video, target)
    }
}
