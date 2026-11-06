package com.wurao.core.analyze

import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope

/**
 * 从"你指的那个控件"生成规则 —— 检视层的核心。
 *
 * 两件事决定这条规则能用多久：
 * 1. **降级链**：不只生成一个选择器，而是生成一串，从最稳的往下试。
 *    App 改版把资源名改了，第二级（文案）还能命中。
 * 2. **排序按抗改版能力**：资源名 → 文案 → 描述 → 类名（架构里定的）。
 *
 * 为什么"容器限定"排第一：真实界面上同一个文案可能出现在好几个地方
 * （美团首页内容区也有"视频"两个字），**不锁容器就会误伤** —— 这条是实测出来的。
 */
object RuleGen {

    /** 一个控件的关键信息（无障碍侧或进程内 View 侧各自填一次）。 */
    data class NodeInfo(
        val className: String,
        val text: String? = null,
        val desc: String? = null,
        /** 资源短名（GKD 的 vid），没有 id 时为 null。 */
        val vid: String? = null,
        val clickable: Boolean = false,
        /** 屏幕区域 "l,t,r,b"：自绘界面没有 text/desc/vid 时靠位置定位。 */
        val rect: String? = null,
    )

    /**
     * 生成降级链（按抗改版能力从强到弱）。
     *
     * @param node 你选中的控件
     * @param container 它的容器（可选）。给了就会多一级"先锁容器再找"的写法。
     */
    fun candidates(node: NodeInfo, container: NodeInfo? = null): List<String> {
        val out = LinkedHashSet<String>()

        // 容器自己的定位（要能锁住它，才谈得上"在它里面找"）
        val containerSel = container?.let { c ->
            c.vid?.let { "[vid=\"$it\"]" }
        }

        // 目标节点可用的属性，按抗改版能力排：资源名 → 文案 → 描述 → 类名
        val selfSelectors = LinkedHashSet<String>()
        node.vid?.let { selfSelectors += "[vid=\"$it\"]" }
        node.text?.takeIf { it.isNotBlank() }?.let { selfSelectors += "[text=\"$it\"]" }
        node.desc?.takeIf { it.isNotBlank() }?.let { selfSelectors += "[desc=\"$it\"]" }
        // 类名兜底**只在没有任何更好特征时才用**：底栏里往往有好几个同类型的 View，
        // 按类名选会选错（实测过），所以有文案/id 就绝不加这一级。
        if (selfSelectors.isEmpty()) {
            // 自绘 App（如摩托范）的节点只有 className，纯类名会命中满屏同类 View。
            // 有屏幕区域时优先按位置锁定，类名只留作兜底。
            node.rect?.takeIf { it.isNotBlank() }?.let { selfSelectors += "[rect=\"$it\"]" }
            selfSelectors += "[name=\"${node.className}\"]"
        }

        // ① 容器 + 自身属性（最稳，优先）
        if (containerSel != null) selfSelectors.forEach { out += "$containerSel << $it" }
        // ② 只用自身属性（容器改版了还能用）
        out += selfSelectors

        return out.toList()
    }

    /**
     * 生成规则（一条降级链拆成多条规则，执行时按顺序试 —— 第一条命中的才动手）。
     *
     * @param groupKey 同一批抓出来的用同一个组 key，便于界面上按组查看/删除
     */
    fun rules(
        pkg: String,
        node: NodeInfo,
        container: NodeInfo? = null,
        act: Act = Act.HIDE,
        groupKey: String = "mine",
    ): List<Rule> = candidates(node, container).map { sel ->
        Rule(
            domain = Domain.NODE,
            target = sel,
            act = act,
            scope = Scope.App(pkg),
            origin = Origin.User,
            groupKey = groupKey,
            // 用选择器本身做 key：同一个 App 抓不同控件时不会互相覆盖，
            // 重复抓同一个控件也不会翻倍
            ruleKey = sel.hashCode().toString(),
            note = noteOf(node, act),
        )
    }

    private fun noteOf(node: NodeInfo, act: Act): String {
        val what = node.text?.takeIf { it.isNotBlank() }
            ?: node.desc?.takeIf { it.isNotBlank() }
            ?: node.className.substringAfterLast('.')
        return when (act) {
            Act.HIDE -> "藏掉「$what」"
            Act.CLICK -> "点掉「$what」"
            Act.REMOVE -> "摘掉「$what」"
            else -> "${act.key}「$what」"
        }
    }
}
