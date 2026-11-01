package com.wurao.core.analyze

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.selector.SelectorMatcher
import com.wurao.core.selector.SelectorParser
import com.wurao.core.selector.XmlNodeAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 检视层核心的闭环验证：**真机界面 → 生成规则 → 用规则再匹配回同一个节点**。
 *
 * 用的是美团首页的真实 dump，节点就是那个底栏"视频" tab —— 也就是你要隐藏的入口。
 */
class RuleGenTest {

    private val root by lazy { XmlNodeAdapter.rootOf("meituan_home.xml") }

    /** 底栏里 desc="视频" 的那个 tab（它是 android.view.View，没有 resource-id） */
    private val videoTab: Element by lazy {
        XmlNodeAdapter.find(root) { it.getAttribute("content-desc") == "视频" }
            ?: error("dump 里找不到视频 tab")
    }

    /** 底栏容器（有资源 id） */
    private val tabBar: Element by lazy {
        XmlNodeAdapter.find(root) { it.getAttribute("resource-id").endsWith("bottom_tab_block") }
            ?: error("dump 里找不到底栏容器")
    }

    private fun info(e: Element) = RuleGen.NodeInfo(
        className = e.getAttribute("class"),
        text = e.getAttribute("text").takeIf { it.isNotEmpty() },
        desc = e.getAttribute("content-desc").takeIf { it.isNotEmpty() },
        vid = e.getAttribute("resource-id").substringAfterLast('/').takeIf { it.isNotEmpty() },
    )

    // ---------------- 生成的规则要能在真机上匹配回去 ----------------

    @Test
    fun 生成的第一条规则就能匹配回那个节点() {
        val sels = RuleGen.candidates(info(videoTab), info(tabBar))
        val first = sels.first()
        val hit = SelectorMatcher(XmlNodeAdapter()).match(SelectorParser(first).parse(), root)
        assertNotNull("第一条规则就该命中：$first", hit)
        assertEquals("命中的必须是视频 tab 本身", "视频", hit?.getAttribute("content-desc"))
    }

    @Test
    fun 第一条是容器限定的写法_因为不锁容器会误伤() {
        // 首页内容区也有"视频"两个字，只按 desc 找会误伤它（实测过）
        val first = RuleGen.candidates(info(videoTab), info(tabBar)).first()
        assertTrue("要先锁底栏容器：$first", first.startsWith("[vid=\"bottom_tab_block\"]"))
        assertTrue(first.contains("[desc=\"视频\"]"))
    }

    @Test
    fun 降级链里每一条都能匹配到东西() {
        val sels = RuleGen.candidates(info(videoTab), info(tabBar))
        assertTrue("要有多级降级：${sels.size}", sels.size >= 2)
        val adapter = XmlNodeAdapter()
        sels.forEach { sel ->
            val hit = SelectorMatcher(adapter).match(SelectorParser(sel).parse(), root)
            assertNotNull("降级链每一级都该能命中：$sel", hit)
        }
    }

    @Test
    fun 降级链不会退到按类名找_底栏有多个同类View会选错() {
        val sels = RuleGen.candidates(info(videoTab), info(tabBar))
        assertTrue(
            "有 desc 就不该用类名兜底：${sels.joinToString()}",
            sels.none { it.contains("[name=") }
        )
    }

    // ---------------- 生成的规则本身要合法 ----------------

    @Test
    fun 生成的规则都能通过校验() {
        val rules = RuleGen.rules("com.sankuai.meituan", info(videoTab), info(tabBar), Act.HIDE)
        assertTrue(rules.isNotEmpty())
        rules.forEach { r ->
            assertTrue("规则必须合法：${r.validate()}", r.validate().isEmpty())
            assertEquals(Domain.NODE, r.domain)
            assertEquals(Act.HIDE, r.act)
            assertEquals("用户自抓的", Origin.User, r.origin)
        }
    }

    @Test
    fun 同一批生成的规则共用组key_便于界面按组删除() {
        val rules = RuleGen.rules("com.sankuai.meituan", info(videoTab), info(tabBar), Act.HIDE, groupKey = "g1")
        assertTrue(rules.all { it.groupKey == "g1" })
        assertEquals("序号要不同，否则 id 撞车", rules.size, rules.map { it.id }.distinct().size)
    }

    @Test
    fun 生成的是应用专属规则_不污染别的应用() {
        val rules = RuleGen.rules("com.sankuai.meituan", info(videoTab), info(tabBar), Act.HIDE)
        assertTrue(rules.all { !it.enabledFor("com.other.app") })
        assertTrue(rules.all { it.enabledFor("com.sankuai.meituan") })
    }

    @Test
    fun 备注要一句人话_排错时看得懂() {
        val rules = RuleGen.rules("com.sankuai.meituan", info(videoTab), info(tabBar), Act.HIDE)
        assertTrue("备注：${rules.first().note}", rules.first().note.contains("视频"))
    }

    // ---------------- 没有容器时也能生成 ----------------

    @Test
    fun 不给容器时退化为只用自身属性() {
        val sels = RuleGen.candidates(info(videoTab), null)
        assertEquals("[desc=\"视频\"]", sels.first())
        assertNotNull(SelectorMatcher(XmlNodeAdapter()).match(SelectorParser(sels.first()).parse(), root))
    }

    @Test
    fun 有资源id时优先用资源id_它最抗改版() {
        val node = RuleGen.NodeInfo(className = "android.view.View", vid = "tab_video", desc = "视频")
        assertEquals("[vid=\"tab_video\"]", RuleGen.candidates(node, null).first())
    }
}
