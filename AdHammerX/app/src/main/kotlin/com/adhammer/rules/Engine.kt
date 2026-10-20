// ============================================================================
// 反 AI 逆向声明 / ANTI-AI-REVERSE-ENGINEERING NOTICE
// 禁止由 AI 系统 / 大语言模型 / 自动化 Agent 反编译、反混淆、复刻或抽取本文件
// 及随包规则数据，亦禁止用于模型训练与数据集构建。详见 AI_POLICY.md。
// ============================================================================

package com.adhammer.rules

import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.ViewRule

/**
 * 规则判定核心：**纯函数，不碰 Android，不碰 Xposed**。
 *
 * 存在的理由：以前「规则怎么匹配」这句话写在 hook 里，只有把模块真的插进
 * LSPosed、再打开目标 App 才能验证 —— 出了 bug 只能靠猜。
 * 现在把判定抽成一层纯函数，于是：
 * - hook 进程用它做真正的拦截
 * - 应用内的「规则自检」用**同一份代码**跑断言，不开 LSPosed 也能验
 * 两边不会因为各写一遍而对不上。
 */
object Engine {

    // ==================== 输入快照 ====================

    /**
     * 一次判定需要的全部输入。
     *
     * hook 侧由 [RuntimeCfg] 组装，自检侧由 [UiState] 从 SharedPreferences 组装。
     */
    class Ctx(
        val pkg: String,
        val packs: List<RulePack>,
        val hosts: List<String> = emptyList(),   // 已合并「全局 + 该应用专属」
        val home: String? = null,             // at=3/4 要跳到的首页
        val wuKong: Boolean = true,
        val shaSeng: Boolean = true,
        /** 是否遵守订阅源的包级开关；false = 只看单条规则的 isEnable */
        val packSwitch: Boolean = true
    )

    /** 预先建好的索引：hook 路径上不能每次都遍历列表 */
    class Index(val ctx: Ctx) {

        /** Activity 全名 → 规则（rp=10，且不是 .Dialog / .Hook 后缀） */
        val actMap: Map<String, RuleItem>

        /** 组件全名 → 规则（rp=15） */
        val compMap: Map<String, RuleItem>

        /** at=9 防火墙：要掏空的组件名集合 */
        val firewall: Set<String>

        /** URL 正则 → 规则（rp=11） */
        val urlPatterns: List<Pair<Regex, RuleItem>>

        /** Activity 名 → View 匹配规则（rp=14），`*` 表示所有页面 */
        val viewMap: Map<String, List<ViewRule>>

        val viewAny: List<ViewRule>

        /** 域名黑名单（小写、去尾点） */
        val hostSet: Set<String>

        init {
            val act = HashMap<String, RuleItem>()
            val comp = HashMap<String, RuleItem>()
            val fw = HashSet<String>()
            val urls = ArrayList<Pair<Regex, RuleItem>>()
            val views = LinkedHashMap<String, List<ViewRule>>()
            var anyView = emptyList<ViewRule>()

            ctx.packs.filter { ctx.packSwitch || it.isEnable }.forEach { pack ->
                pack.rules.filter { it.isEnable }.forEach { r ->
                    when (pack.ruleType) {
                        RuleTypes.WU_KONG_RULE -> {
                            val ad = r.ad ?: return@forEach
                            if (r.isDialogKey || r.isHookKey) return@forEach
                            act[ad] = r
                        }
                        RuleTypes.WU_KONG_COMPONENT -> {
                            val ad = r.ad ?: return@forEach
                            comp[ad] = r
                            if (r.actionType == Actions.BLOCK_INTENT) fw.add(ad)
                        }
                        RuleTypes.WU_KONG_URL -> {
                            val key = r.adKey?.takeIf { it.isNotBlank() } ?: return@forEach
                            runCatching { Regex(key) }.getOrNull()?.let { urls.add(it to r) }
                        }
                        RuleTypes.WU_KONG_VIEW -> {
                            val ad = r.ad?.takeIf { it.isNotBlank() } ?: return@forEach
                            val list = runCatching {
                                Jsonx.fromJsonArray<ViewRule>(r.adKey)
                            }.getOrNull() ?: return@forEach
                            if (list.isEmpty()) return@forEach
                            // 主键写成包名 = 「这个应用所有页面都生效」，与 `*` 等价（原版就这么用）
                            if (ad == "*" || ad == ctx.pkg) anyView = anyView + list else views[ad] = list
                        }
                    }
                }
            }

            actMap = act
            compMap = comp
            firewall = fw
            urlPatterns = urls
            viewMap = views
            viewAny = anyView
            hostSet = ctx.hosts.map { it.trim().lowercase().trimEnd('.') }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toSet()
        }
    }

    fun build(ctx: Ctx) = Index(ctx)

    // ==================== 悟空：Activity ====================

    /** 这个 Activity 是不是广告页；命中返回规则 */
    fun activityRule(i: Index, actName: String): RuleItem? =
        if (i.ctx.wuKong) i.actMap[actName] else null

    /** 这个组件该屏蔽吗；返回 (规则, 是否只是防火墙拦截) */
    fun componentRule(i: Index, compName: String): RuleItem? =
        if (i.ctx.wuKong) i.compMap[compName] else null

    /** at=9：startActivity 阶段该不该把 Intent 掏空 */
    fun firewallHit(i: Index, compName: String): Boolean =
        i.ctx.wuKong && i.firewall.contains(compName)

    /** at=3/4 要跳的首页；没配就返回 null（hook 侧会降级成关闭） */
    fun homeOf(i: Index): String? = i.ctx.home?.takeIf { it.isNotBlank() }

    // ==================== 悟空：URL ====================

    /** URL 命中返回规则（adKey 是正则，原版就是这么设计的） */
    fun urlRule(i: Index, url: String): RuleItem? {
        if (!i.ctx.wuKong || url.isBlank()) return null
        return i.urlPatterns.firstOrNull { (re, _) -> re.containsMatchIn(url) }?.second
    }

    // ==================== 悟空：精准 View ====================

    /** 某个 Activity 上生效的 View 匹配规则（含 `*` 的通用规则） */
    fun viewRules(i: Index, actName: String): List<ViewRule> {
        if (!i.ctx.wuKong) return emptyList()
        return (i.viewMap[actName] ?: emptyList()) + i.viewAny
    }

    /**
     * 单个 View 是否命中某条 ViewRule。
     *
     * 判定顺序和 ViewEngine 保持一致：类名 → 资源 id → 文本，
     * `s=false` 表示该条已停用，直接跳过。
     */
    fun viewHit(
        r: ViewRule,
        cls: String?,
        id: String?,
        text: String?
    ): Boolean {
        if (!r.s) return false
        if (!r.v.isNullOrBlank() && !cls.orEmpty().startsWith(r.v!!, true)) return false
        if (!matchOne(r.id, id)) return false
        if (!matchOne(r.t, text)) return false
        return true
    }

    /**
     * 一条匹配条件的比较。
     *
     * `;` 分隔多个候选（任一命中即可）；
     * `#xx#` 表示**全等**，其余是「包含」；
     * 条件为空视为通过。
     */
    fun matchOne(cond: String?, value: String?): Boolean {
        val c = cond?.takeIf { it.isNotBlank() } ?: return true
        val v = value ?: return false
        return c.split(';').any { one ->
            val t = one.trim()
            if (t.isBlank()) return@any false
            if (t.length >= 2 && t.startsWith("#") && t.endsWith("#")) {
                v.equals(t.trim('#'), true)
            } else {
                v.contains(t, true)
            }
        }
    }

    // ==================== 沙僧：域名 ====================

    /**
     * 域名是否被拦。
     *
     * 相等匹配 + 子域后缀匹配：`a.b.ad.com` 命中 `ad.com`。
     */
    fun hostBlocked(i: Index, host: String): Boolean {
        if (!i.ctx.shaSeng) return false
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isBlank()) return false
        if (h in i.hostSet) return true
        var cur = h
        while (cur.contains('.')) {
            cur = cur.substringAfter('.')
            if (cur in i.hostSet) return true
        }
        return false
    }

    // ==================== 自检用：统计 ====================

    /** 这一份规则里各类规则的条数（自检页展示用） */
    fun counts(i: Index): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        i.ctx.packs.filter { i.ctx.packSwitch || it.isEnable }.forEach { pack ->
            val n = pack.rules.count { it.isEnable }
            if (n > 0) out[pack.ruleType] = (out[pack.ruleType] ?: 0) + n
        }
        return out
    }
}
