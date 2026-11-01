package com.wurao.core.model

/**
 * 生效范围。
 *
 * 采用 GKD 的模型：**全局规则默认对全部应用生效，用例外名单关掉误伤的应用**
 * （规模随「误伤」增长），而不是「每个应用都要有人写规则」（规模随「应用数」增长，
 * 订阅一停更就整体失效 —— 旧库停更 21 个月已经证明过一次）。
 */
sealed interface Scope {
    /** 对所有被接管的应用生效；具体某个应用是否被排除，查 [Exclusion]。 */
    data object Global : Scope

    /** 只对某个应用生效。 */
    data class App(val pkg: String) : Scope

    /** 只对某个应用的某个页面生效。 */
    data class Component(val pkg: String, val activityId: String) : Scope

    /** 这条规则在 key 里的作用域段，参与 id 构成。 */
    val scopeKey: String
        get() = when (this) {
            Global -> "*"
            is App -> pkg
            is Component -> "$pkg/$activityId"
        }
}

/** 例外名单：全局规则在某个应用上被关掉（GKD 的 apps[].enable=false）。 */
data class Exclusion(
    val pkg: String,
    /** false = 关掉（默认全开，所以只需要记「关掉的」）。 */
    val enable: Boolean = false,
)

/** 计数重置口径（对应 GKD 的 resetMatch）。 */
enum class ResetBy(val key: String) {
    /** 只在首次进入该应用时重置。 */
    APP("app"),

    /** 每次 Activity 切换重置（默认）。 */
    ACTIVITY("activity"),

    /** 仅当之前没匹配过才重置。 */
    MATCH("match"),
    ;

    companion object {
        fun fromKey(key: String): ResetBy? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 时序参数。规则没写时由调用方（档位 / 订阅默认值）决定，这里只存「规则自己写了什么」。
 * 默认值取自 GKD 实测口径：冷却 1s、按 Activity 重置、不限次数。
 */
data class Timing(
    /** 命中后延迟多久执行。 */
    val delayMs: Long = 0L,

    /** 同一条规则的两次动作之间的冷却。 */
    val cooldownMs: Long = 1_000L,

    /** 一个页面内最多动手几次；null = 不限。 */
    val maxActions: Int? = null,

    /** 计数什么时候重置。 */
    val resetBy: ResetBy = ResetBy.ACTIVITY,

    /** 进入页面后只匹配这么久；null = 不限。 */
    val matchWindowMs: Long? = null,
)

/**
 * 规则来源。决定 id 前缀与去重身份 —— **源只是「往里导」的入口**，
 * 导完不保留源数据，因此不存在「重算冲掉用户手写」这类 bug。
 */
sealed interface Origin {
    /** 订阅（GKD / 域名表 / 组件表等，见 core/source 的可插拔适配器）。 */
    data class Subscription(val subsId: Long) : Origin

    /** 用户自己抓的。 */
    data object User : Origin

    /** 内置（内置启发式、开屏那两条全局选择器）。 */
    data object Builtin : Origin

    /** 种子数据（如旧库抽出的 2444 条组件表）。 */
    data class Seed(val name: String) : Origin

    val idPrefix: String
        get() = when (this) {
            is Subscription -> "s$subsId"
            User -> "u"
            Builtin -> "b"
            is Seed -> "seed:${name}"
        }
}

/** 规则校验发现的问题。**不允许静默失败** —— 解析不出来、组合非法都要报出来。 */
data class Issue(val field: String, val message: String) {
    override fun toString(): String = "$field: $message"
}

/**
 * 统一规则模型 —— 五个域共用这**一个**结构。
 *
 * 要加一种新能力，先问「它是哪个域 + 哪个动作」；只有当现有域表达不了时才加域，
 * 且加域 = 加匹配器 + 触发点，**不改这个结构**。
 */
data class Rule(
    val domain: Domain,
    val target: String,
    val act: Act,
    val scope: Scope = Scope.Global,
    val timing: Timing = Timing(),
    val origin: Origin,

    /** 组 key（订阅的 group / 用户抓的一次会话）。 */
    val groupKey: String = "0",

    /** 组内序号或订阅的 rule key。 */
    val ruleKey: String = "0",

    /** GOTO 的落地组件；其它动作为 null。 */
    val gotoTarget: String? = null,

    /**
     * 页面限定（对应 GKD 的 activityIds）：只在这些 Activity 里生效，空 = 不限页面。
     * **这是进程内最便宜的一道闸** —— 订阅里 78% 的规则写了它，
     * 页面不对就整条跳过，一次匹配都不做（耗电约束见 plan 第八节）。
     */
    val activityIds: List<String> = emptyList(),

    /** 一句人话，给界面和日志看。 */
    val note: String = "",

    /** 快照引用：这条规则当初针对的界面（可复核性，见调研报告 GKD 部分）。 */
    val snapshot: String? = null,

    /** 用户或例外名单关掉时为 false。**关掉的规则保留在数据里，不删** —— 删了就找不回来。 */
    val enabled: Boolean = true,
) {
    /**
     * 规则身份。导入时先删同 id 再写 —— 同一个源点十次还是那一份（重复导入不翻倍）。
     */
    val id: String get() = "${origin.idPrefix}:${scope.scopeKey}:$groupKey:$ruleKey"

    /**
     * 校验。返回空列表表示可用。
     * 调用方**必须**处理返回的 issue 并上报计数，不许忽略。
     */
    fun validate(): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (target.isBlank()) {
            issues += Issue("target", "为空（${domain.targetHint}）")
        }
        if (!act.allows(domain)) {
            issues += Issue("act", "${act.key} 不允许出现在 ${domain.key} 域（允许：${act.allowed.joinToString { it.key }}）")
        }
        if (act == Act.GOTO && gotoTarget.isNullOrBlank()) {
            issues += Issue("gotoTarget", "GOTO 必须给落地组件")
        }
        if (act != Act.GOTO && gotoTarget != null) {
            issues += Issue("gotoTarget", "只有 GOTO 才需要落地组件")
        }
        if (timing.cooldownMs < 0) issues += Issue("timing.cooldownMs", "不能为负")
        if (timing.delayMs < 0) issues += Issue("timing.delayMs", "不能为负")
        if (timing.maxActions != null && timing.maxActions <= 0) {
            issues += Issue("timing.maxActions", "必须为正")
        }
        if (timing.matchWindowMs != null && timing.matchWindowMs <= 0) {
            issues += Issue("timing.matchWindowMs", "必须为正")
        }
        return issues
    }

    /**
     * 页面闸：当前 Activity 是否是这条规则指定的页面。
     * 没写 activityIds 就是不限页面（返回 true）。
     */
    fun matchesPage(activityName: String?): Boolean {
        if (activityIds.isEmpty()) return true
        if (activityName.isNullOrBlank()) return false
        return activityIds.any { it == activityName || activityName.endsWith(it) }
    }

    /** 这条规则在某个应用上是否启用（考虑例外名单）。 */
    fun enabledFor(pkg: String, exclusions: Map<String, Exclusion> = emptyMap()): Boolean {
        if (!enabled) return false
        return when (scope) {
            Scope.Global -> exclusions[pkg]?.enable != false
            is Scope.App -> scope.pkg == pkg
            is Scope.Component -> scope.pkg == pkg
        }
    }
}
