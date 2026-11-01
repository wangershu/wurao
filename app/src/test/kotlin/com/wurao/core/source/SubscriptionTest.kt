package com.wurao.core.source

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.store.RuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅导入不变量。样例按 GKD 真实订阅的写法构造（无引号 key、单引号串、尾逗号、注释）。
 */
class SubscriptionTest {

    private val sample = """
        // 测试用订阅
        {
          id: 667,
          name: '测试订阅',
          version: 3,
          globalGroups: [{
            key: 0,
            name: '开屏广告',
            actionMaximum: 2,
            matchTime: 10000,
            rules: [
              { key: 0, matches: '[text*="跳过"][text.length<10][visibleToUser=true]' },
              { key: 1, action: 'back', matches: '[vid="ad_back"]' },
            ],
            apps: [
              { id: 'com.tencent.mm', enable: false },
              { id: 'com.android.systemui', enable: false },
            ],
          }],
          apps: [
            { id: 'com.sankuai.meituan', groups: [{
              key: 10,
              name: '美团广告',
              activityIds: ['com.meituan.MainActivity'],
              rules: [
                { key: 0, matches: '[vid^="tab_"]', action: 'click' },
                { key: 1, matches: '[text="广告"]', action: 'swipe' },
              ],
            }]},
          ],
        }
    """.trimIndent()

    // ---------- JSON5 ----------

    @Test
    fun json5_能读无引号key与单引号字符串() {
        val o = Json5.parseObject("{id:667,name:'测试'}")
        assertEquals(667L, o["id"])
        assertEquals("测试", o["name"])
    }

    @Test
    fun json5_尾逗号与注释不报错() {
        val o = Json5.parseObject("""{a:[1,2,], // 注释
            /* 块注释 */ b:true}""")
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(1L, 2L), o["a"] as List<Any?>)
        assertEquals(true, o["b"])
    }

    // ---------- 订阅解析 ----------

    @Test
    fun 总数守恒_解析出来的加跳过等于总数() {
        val r = GkdSubscription.parse(sample)
        assertTrue("守恒：${r.rules.size}+${r.skipped.size}=${r.total}", r.ok)
        assertEquals(4, r.total)
    }

    @Test
    fun 全局组默认对所有应用生效() {
        val r = GkdSubscription.parse(sample)
        val global = r.rules.filter { it.scope == Scope.Global }
        assertEquals(2, global.size)
        assertTrue(global.all { it.act == Act.CLICK || it.act == Act.BACK })
    }

    @Test
    fun 例外名单被读出来() {
        val r = GkdSubscription.parse(sample)
        assertTrue(r.exclusions.containsKey("com.tencent.mm"))
        assertEquals(false, r.exclusions["com.tencent.mm"]?.enable)
    }

    @Test
    fun 时序参数按GKD语义映射() {
        val r = GkdSubscription.parse(sample)
        val g = r.rules.first { it.scope == Scope.Global }
        assertEquals(2, g.timing.maxActions)
        assertEquals(10_000L, g.timing.matchWindowMs)
        assertEquals(1_000L, g.timing.cooldownMs)   // GKD 默认冷却 1 秒
    }

    @Test
    fun 动作不支持时进跳过_并且写明原因() {
        val r = GkdSubscription.parse(sample)
        val s = r.skipped.first()
        assertTrue("跳过原因要说明白：${s.reason}", s.reason.contains("动作不支持"))
        assertTrue(s.reason.contains("swipe"))
    }

    @Test
    fun 页面限定被读出来_页面闸靠它() {
        val r = GkdSubscription.parse(sample)
        val appRule = r.rules.first { it.scope is Scope.App }
        assertEquals(listOf("com.meituan.MainActivity"), appRule.activityIds)
        assertTrue(appRule.matchesPage("com.meituan.MainActivity"))
        assertTrue("不写页面限定的规则不限页面",
            r.rules.first { it.scope == Scope.Global }.matchesPage("任意页面"))
    }

    // ---------- 规则库：幂等与不覆盖 ----------

    @Test
    fun 重复导入不翻倍() {
        RuleStore.clear()
        RuleStore.import(GkdSubscription.parse(sample))
        val first = RuleStore.all().size
        RuleStore.import(GkdSubscription.parse(sample))
        assertEquals("同一份订阅点两次还是那一份", first, RuleStore.all().size)
    }

    @Test
    fun 订阅更新不会覆盖用户自抓的规则() {
        RuleStore.clear()
        RuleStore.import(GkdSubscription.parse(sample))
        val mine = Rule(
            domain = Domain.NODE,
            target = """[text="我抓的广告"]""",
            act = Act.HIDE,
            scope = Scope.App("com.sankuai.meituan"),
            origin = Origin.User,
            groupKey = "mine",
            ruleKey = "0",
        )
        RuleStore.upsert(mine)
        RuleStore.import(GkdSubscription.parse(sample))   // 再导一次订阅
        assertTrue("自己抓的还在", RuleStore.get(mine.id) != null)
    }

    @Test
    fun 停用不等于删除_规则还在库里() {
        RuleStore.clear()
        RuleStore.import(GkdSubscription.parse(sample))
        val id = RuleStore.all().first().id
        RuleStore.setEnabled(id, false)
        assertTrue(RuleStore.get(id) != null)
        assertTrue(RuleStore.forApp("com.sankuai.meituan").none { it.id == id })
    }

    // ---------- 域名表 ----------

    @Test
    fun 域名表_三种常见写法都能认出来() {
        val text = """
            # 注释
            ||ads.example.com^
            0.0.0.0 ads2.example.com
            ads3.example.com, 备注
            ! 另一条注释
        """.trimIndent()
        val r = HostList.parse(text)
        assertTrue(r.hosts.contains("ads.example.com"))
        assertTrue(r.hosts.contains("ads2.example.com"))
        assertTrue(r.hosts.contains("ads3.example.com"))
        assertEquals("注释行不算忽略行", 0, r.ignoredLines)
    }

    @Test
    fun 域名表_认不出的行被记数而不是整体失败() {
        val r = HostList.parse("这是一行废话\n||ok.example.com^")
        assertEquals(1, r.ignoredLines)
        assertTrue(r.hosts.contains("ok.example.com"))
    }

    @Test
    fun 域名表_重复项去重() {
        val r = HostList.parse("||a.example.com^\n||a.example.com^")
        assertEquals(1, r.hosts.size)
    }
}
