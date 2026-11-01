package com.wurao.core.model

/**
 * 匹配域：这条规则「找的是什么东西」。
 *
 * 三层能力（L0 渲染前 / L1 全局 / L2 自抓）机制各不相同，能统一的只有规则结构与执行接口。
 * 差异全部收在这里：每个域换一个匹配器实现，**规则结构、存储、生效判定、健康度、诊断共用**。
 * 新增一个域 = 加一个枚举 + 一个匹配器 + 一个触发点，不动模型。
 */
enum class Domain(
    val key: String,
    /** target 字段写什么（ humans 可读的说明，用于报错与文档） */
    val targetHint: String,
) {
    /** 组件 / 页面：拦截 startActivity、禁用组件、改跳转目标。属于 L0。 */
    COMPONENT("component", "组件全名，如 com.x.ad.SplashActivity"),

    /** 广告 SDK 的类与方法：让 init / loadAd / show 不发生。属于 L0。 */
    SDK("sdk", "类#方法，如 com.bytedance...TTAdSdk#init；或 DexKit 查询描述"),

    /** 域名：解析到本地 / 请求不出门。属于 L0。 */
    HOST("host", "域名，如 ads.doubleclick.net"),

    /** 文件路径：广告缓存替换成不可读写。属于 L0。 */
    FILE("file", "广告缓存路径"),

    /** 控件节点：用选择器匹配 View 树。属于 L1 / L2。 */
    NODE("node", "GKD 选择器串，如 [text*=\"跳过\"][text.length<10]"),
    ;

    companion object {
        fun fromKey(key: String): Domain? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 动作：命中之后做什么。**只有这 7 个**。
 *
 * 旧实现有 13 个动作，复杂度摊在枚举上，是 bug 的主要来源之一。
 * 降级是执行细节不是枚举 —— 例如 CLICK 的「先语义点击、失败退坐标点击」，
 * 定位链的抗改版排序，都不进这里。
 */
enum class Act(
    val key: String,
    /** 允许出现在哪些域。矩阵写死在这里，新域新动作必须同步更新。 */
    val allowed: Set<Domain>,
) {
    /** 让它不发生：组件不起来 / 方法不执行 / 请求不出门 / 路径不可读写。 */
    BLOCK("block", setOf(Domain.COMPONENT, Domain.SDK, Domain.HOST, Domain.FILE)),

    /** 拦下并改跳转到指定组件（原版 at=3/7「跳首页」）。 */
    GOTO("goto", setOf(Domain.COMPONENT)),

    /** 点击。执行时先尝试语义点击，失败退坐标点击（降级是实现细节）。 */
    CLICK("click", setOf(Domain.NODE)),

    LONG_CLICK("long_click", setOf(Domain.NODE)),

    /** 返回键。 */
    BACK("back", setOf(Domain.NODE, Domain.COMPONENT)),

    /** 隐藏：GONE + 尺寸归零。 */
    HIDE("hide", setOf(Domain.NODE)),

    /** 从父容器摘除。 */
    REMOVE("remove", setOf(Domain.NODE)),
    ;

    fun allows(domain: Domain): Boolean = domain in allowed

    companion object {
        fun fromKey(key: String): Act? = entries.firstOrNull { it.key == key }
    }
}
