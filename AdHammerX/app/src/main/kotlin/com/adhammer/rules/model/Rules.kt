package com.adhammer.rules.model

import com.google.gson.annotations.SerializedName

/**
 * 订阅规则模型。
 *
 * 字段名与订阅源（dy_ys10.zip）中的 JSON **完全同名**，
 * 由 Gson 直接反序列化，不做任何改名，保证换源、加字段都不会失效。
 *
 * 订阅包结构：一个 JSON 数组，每个元素 = 一个 App 的规则包 [RulePack]。
 */

// ==================== 规则分类（ruleType） ====================

object RuleTypes {
    /** 悟空：通用规则（关闭/禁用/跳过/改写/Dialog/自定义Hook） */
    const val WU_KONG_RULE = 10
    /** 悟空：URL 拦截 */
    const val WU_KONG_URL = 11
    /** 悟空：精准 View 屏蔽 */
    const val WU_KONG_VIEW = 14
    /** 悟空：组件屏蔽（四大组件 + Intent 拦截） */
    const val WU_KONG_COMPONENT = 15
    /** 悟空：文件替换 */
    const val WU_KONG_REPLACE = 20
    /** 八戒：自动化任务 */
    const val BA_JIE_TASK = 1111
}

// ==================== 动作类型（actionType） ====================

object Actions {
    /** at=1 关闭活动页：finish。d>0 时延迟后按返回键。会有一闪而过 */
    const val CLOSE_ACT = 1
    /** at=2 禁用活动页：组件 DISABLED。可能引起闪退 */
    const val DISABLE_ACT = 2
    /** at=3 跳过到首页：启动 home 并 finish 当前页 */
    const val SKIP_TO_HOME = 3
    /** at=4 改写跳转目标 */
    const val RETARGET = 4
    /** at=5 弹框规则（ad 以 .Dialog 结尾） */
    const val DIALOG = 5
    /** at=6 自定义 Hook（ad 以 .Hook 结尾，adKey 可能是加密脚本） */
    const val CUSTOM_HOOK = 6
    /** at=8 精准 View 屏蔽 */
    const val VIEW = 8
    /** at=9 防火墙拦截：startActivity 阶段掏空 Intent，效果最好 */
    const val BLOCK_INTENT = 9

    /** at=500 屏蔽 Activity 组件 */
    const val BLOCK_ACTIVITY = 500
    /** at=501 屏蔽 Receiver 组件 */
    const val BLOCK_RECEIVER = 501
    /** at=502 屏蔽 Service 组件 */
    const val BLOCK_SERVICE = 502
    /** at=503 屏蔽 Provider 组件 */
    const val BLOCK_PROVIDER = 503

    /** 文件替换：替换为空文件夹 */
    const val REPLACE_DIR = 1
    /** 文件替换：替换为空文件 */
    const val REPLACE_FILE = 2
    /** 文件替换：删除 */
    const val REPLACE_DELETE = 3

    fun describe(actionType: Int): String = when (actionType) {
        CLOSE_ACT -> "关闭活动页"
        DISABLE_ACT -> "禁用活动页"
        SKIP_TO_HOME -> "跳过到首页"
        RETARGET -> "改写跳转目标"
        DIALOG -> "弹框规则"
        CUSTOM_HOOK -> "自定义Hook"
        VIEW -> "精准View屏蔽"
        BLOCK_INTENT -> "防火墙拦截"
        BLOCK_ACTIVITY -> "屏蔽Activity"
        BLOCK_RECEIVER -> "屏蔽Receiver"
        BLOCK_SERVICE -> "屏蔽Service"
        BLOCK_PROVIDER -> "屏蔽Provider"
        REPLACE_DIR -> "替换为空文件夹"
        REPLACE_FILE -> "替换为空文件"
        REPLACE_DELETE -> "删除"
        else -> "未知($actionType)"
    }

    /** 是否属于四大组件屏蔽 */
    fun isComponentBlock(actionType: Int) = actionType in BLOCK_ACTIVITY..BLOCK_PROVIDER

    /** 某类规则允许的动作（规则编辑器下拉用） */
    fun optionsFor(ruleType: Int): List<Int> = when (ruleType) {
        RuleTypes.WU_KONG_RULE -> listOf(CLOSE_ACT, DISABLE_ACT, SKIP_TO_HOME, RETARGET, DIALOG, CUSTOM_HOOK)
        RuleTypes.WU_KONG_URL -> listOf(REPLACE_FILE, SKIP_TO_HOME)   // 2=URL 拦截 3=Host 拦截
        RuleTypes.WU_KONG_VIEW -> listOf(VIEW)
        RuleTypes.WU_KONG_COMPONENT -> listOf(BLOCK_INTENT, BLOCK_ACTIVITY, BLOCK_RECEIVER, BLOCK_SERVICE, BLOCK_PROVIDER)
        RuleTypes.WU_KONG_REPLACE -> listOf(REPLACE_DIR, REPLACE_FILE, REPLACE_DELETE)
        else -> listOf(CLOSE_ACT, DISABLE_ACT, SKIP_TO_HOME, RETARGET, VIEW, BLOCK_INTENT)
    }

    /** 主键填写提示 */
    fun adHint(ruleType: Int, actionType: Int): String = when (ruleType) {
        RuleTypes.WU_KONG_RULE -> when (actionType) {
            DIALOG -> "弹框规则：填 xxx.Dialog"
            CUSTOM_HOOK -> "自定义 Hook：填 xxx.Hook"
            else -> "广告 Activity 全名"
        }
        RuleTypes.WU_KONG_URL -> "URL 规则：填 xxx.Url；adKey 填域名或完整 URL"
        RuleTypes.WU_KONG_VIEW -> "广告所在 Activity，填 * 表示所有页面"
        RuleTypes.WU_KONG_COMPONENT -> "要屏蔽的组件全名（Activity/Receiver/Service/Provider）"
        RuleTypes.WU_KONG_REPLACE -> "文件路径（adKey 为空时用这个）"
        else -> "主键"
    }

    /** 规则值填写提示 */
    fun adKeyHint(ruleType: Int, actionType: Int): String = when (ruleType) {
        RuleTypes.WU_KONG_VIEW ->
            "[{\"v\":\"android.widget.TextView\",\"s\":true,\"id\":\"xxx\",\"t\":\"跳过\",\"ht\":1,\"pl\":1}]"
        RuleTypes.WU_KONG_RULE -> when (actionType) {
            DIALOG -> "[{\"at\":1,\"t\":\"关闭\",\"n\":\"com.xxx.AdDialog\",\"d\":0}]"
            CUSTOM_HOOK -> "方法级 Hook 数组（cn/mn/rt/rtt）"
            else -> "可留空"
        }
        RuleTypes.WU_KONG_URL -> "域名，如 ad.xxx.com（ad 填 xxx.Url）"
        RuleTypes.WU_KONG_REPLACE -> "替换后的目标路径"
        else -> "可留空"
    }
}

// ==================== 订阅包 ====================

/** 一个 App 的一份规则包（订阅文件里的一个数组元素）。 */
data class RulePack(
    @SerializedName("isEnable") val isEnable: Boolean = true,
    @SerializedName("name") val name: String? = null,
    @SerializedName("pkg") val pkg: String = "",
    @SerializedName("ruleStat") val ruleStat: Int = 0,
    @SerializedName("ruleType") val ruleType: Int = 0,
    @SerializedName("version") val version: String? = null,
    @SerializedName("rules") val rules: List<RuleItem> = emptyList()
)

/** 单条规则。 */
data class RuleItem(
    @SerializedName("actionType") val actionType: Int = 0,
    /** 主键：Activity 全名 / 文件路径 / xxx.Url / xxx.Dialog / xxx.Hook */
    @SerializedName("ad") val ad: String? = null,
    /** 规则值：JSON 数组（View/Dialog/方法Hook）或 域名 或 路径 */
    @SerializedName("adKey") val adKey: String? = null,
    @SerializedName("delay") val delay: Long = 0,
    @SerializedName("isCommon") val isCommon: Boolean = false,
    @SerializedName("isEnable") val isEnable: Boolean = true,
    @SerializedName("isNotSync") val isNotSync: Boolean = false,
    @SerializedName("localRemark") val localRemark: String? = null,
    @SerializedName("pkg") val pkg: String? = null,
    @SerializedName("remark") val remark: String? = null,
    @SerializedName("ruleType") val ruleType: Int = 0,
    @SerializedName("times") val times: Int = 1,
    @SerializedName("updateTime") val updateTime: String? = null,
    @SerializedName("x") val x: Int = -1,
    @SerializedName("y") val y: Int = -1
) {
    /** ad 以 .Url 结尾 → URL / Host 拦截 */
    val isUrlKey get() = ad?.endsWith(".Url") == true
    /** ad 以 .Dialog 结尾 → 弹框规则 */
    val isDialogKey get() = ad?.endsWith(".Dialog") == true
    /** ad 以 .Hook 结尾 → 自定义 Hook */
    val isHookKey get() = ad?.endsWith(".Hook") == true
    /** ad 以 .Replace 结尾 → 文件替换 */
    val isReplaceKey get() = ad?.endsWith(".Replace") == true

    val displayRemark get() = remark?.takeIf { it.isNotBlank() }
        ?: localRemark?.takeIf { it.isNotBlank() } ?: ""
}

// ==================== 精准 View 规则（adKey 的 JSON 数组元素） ====================

/** 对应原版 ViewHookInfo，解决「跳过按钮没文字也没 id」的场景。 */
data class ViewRule(
    @SerializedName("v") val v: String? = null,          // 入口 View 类名前缀
    @SerializedName("s") val s: Boolean = true,          // 开关
    @SerializedName("id") val id: String? = null,        // 资源名，; 分隔多候选
    @SerializedName("t") val t: String? = null,          // 文本，; 分隔；#xx# 表示全等
    @SerializedName("ht") val ht: Int = 0,               // hook 类型
    @SerializedName("pl") val pl: Int = 0,               // 向上父层数
    @SerializedName("p") val p: String? = null,          // 父容器类名
    @SerializedName("pId") val pId: String? = null,
    @SerializedName("pt") val pt: String? = null,
    @SerializedName("cl") val cl: Int = 0,               // 向下子层数
    @SerializedName("c") val c: String? = null,          // 子 View 类名
    @SerializedName("cId") val cId: String? = null,
    @SerializedName("ct") val ct: String? = null,
    @SerializedName("sq") val sq: String? = null,        // 子序号，; 分隔
    @SerializedName("m") val m: String? = null           // 备注
)

// ==================== 弹框规则（adKey 的 JSON 数组元素） ====================

/** 对应原版 DlgHookInfo。 */
data class DialogRule(
    @SerializedName("at") val at: Int = 0,               // 0禁止 / 1自动取消 / 2自动点击
    @SerializedName("id") val id: String? = null,
    @SerializedName("t") val t: String? = null,
    @SerializedName("n") val n: String? = null,          // Dialog 类名
    @SerializedName("clkTxt") val clkTxt: String? = null,
    @SerializedName("clkId") val clkId: String? = null,
    @SerializedName("d") val d: Long = 0
) {
    val isAutoClick get() = at == 2
    val isAutoCancel get() = at == 1
}

// ==================== 自定义/方法级 Hook（adKey 的 JSON 数组元素，ht=97） ====================

/**
 * 方法级 Hook 规则。
 * ht=97 时表示「按文本定位后调用指定方法 / 或把某方法返回值改掉」。
 */
data class MethodRule(
    @SerializedName("ht") val ht: Int = 0,
    @SerializedName("cn") val cn: String? = null,        // 类名
    @SerializedName("mn") val mn: String? = null,        // 方法名
    @SerializedName("rt") val rt: String? = null,        // 返回类型
    @SerializedName("rtt") val rtt: String? = null,      // 强制返回值
    @SerializedName("args") val args: List<String>? = null,
    @SerializedName("t") val t: String? = null,          // 文本条件
    @SerializedName("s") val s: Boolean = true,
    @SerializedName("c") val c: Int = 0,
    @SerializedName("d") val d: Long = 0,
    @SerializedName("x") val x: Int = 0,
    @SerializedName("y") val y: Int = 0,
    @SerializedName("v") val v: String? = null,
    @SerializedName("fields") val fields: String? = null
)

// ==================== 八戒：自动化任务 ====================

/** 八戒任务：在某个 Activity 上按顺序执行若干步骤。 */
data class Task(
    @SerializedName("act") val act: String? = null,      // 目标 Activity
    @SerializedName("day") val day: Int = 0,
    @SerializedName("isCommon") val isCommon: Boolean = false,
    @SerializedName("isEnable") val isEnable: Boolean = true,
    @SerializedName("isNotSync") val isNotSync: Boolean = false,
    @SerializedName("name") val name: String? = null,
    @SerializedName("pkg") val pkg: String = "",
    @SerializedName("remark") val remark: String? = null,
    @SerializedName("rnd") val rnd: Int = 0,
    @SerializedName("ruleType") val ruleType: Int = 0,
    @SerializedName("steps") val steps: List<Step> = emptyList(),
    @SerializedName("taskType") val taskType: Int = 0,
    @SerializedName("updateTime") val updateTime: String? = null,
    @SerializedName("version") val version: String? = null
)

/** 八戒的单个操作步骤：点击 / 长按 / 滑动 / 坐标点击。 */
data class Step(
    @SerializedName("at") val at: Int = 8,               // 动作类型
    @SerializedName("vId") val vId: String? = null,      // 目标 view id
    @SerializedName("wv") val wv: String? = null,        // 目标控件类名
    @SerializedName("t") val t: String? = null,          // 目标文本
    @SerializedName("clkId") val clkId: String? = null,
    @SerializedName("clkTxt") val clkTxt: String? = null,
    @SerializedName("clkWv") val clkWv: String? = null,
    @SerializedName("ignId") val ignId: String? = null,  // 忽略条件
    @SerializedName("ignTxt") val ignTxt: String? = null,
    @SerializedName("isIgn") val isIgn: Boolean = false,
    @SerializedName("iId") val iId: String? = null,
    @SerializedName("iTxt") val iTxt: String? = null,
    @SerializedName("x") val x: Int = -1,                // 坐标点击
    @SerializedName("y") val y: Int = -1,
    @SerializedName("xf") val xf: Int = -1,
    @SerializedName("yf") val yf: Int = -1,
    @SerializedName("sx0") val sx0: Int = -1,            // 滑动起点
    @SerializedName("sy0") val sy0: Int = -1,
    @SerializedName("sx1") val sx1: Int = -1,            // 滑动终点
    @SerializedName("sy1") val sy1: Int = -1,
    @SerializedName("c") val c: Int = 1,                 // 重复次数
    @SerializedName("d") val d: Long = 0,                // 延迟
    @SerializedName("it") val it: Long = 0,              // 间隔
    @SerializedName("ct") val ct: Int = 0,               // 点击类型
    @SerializedName("lt") val lt: Int = 1,
    @SerializedName("prs") val prs: Boolean = false,     // 长按
    @SerializedName("slt") val slt: Int = 0,
    @SerializedName("spd") val spd: Int = 0,             // 滑动速度
    @SerializedName("uv") val uv: Boolean = false,
    @SerializedName("op") val op: Boolean = false,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("oTop") val oTop: Int = 0,
    @SerializedName("oBtm") val oBtm: Int = 0,
    @SerializedName("leftX") val leftX: Int = 0,
    @SerializedName("rightX") val rightX: Int = 0,
    @SerializedName("topY") val topY: Int = 0,
    @SerializedName("bottomY") val bottomY: Int = 0,
    @SerializedName("chk") val chk: Int = 0,
    @SerializedName("clkChk") val clkChk: Int = 0,
    @SerializedName("rndIt") val rndIt: Int = 0,
    @SerializedName("pRel") val pRel: Boolean = false,
    @SerializedName("searchViews") val searchViews: String? = null,
    @SerializedName("cmd") val cmd: String? = null,
    @SerializedName("sd") val sd: String? = null
)

// ==================== 订阅元信息 ====================

/** 一条拦截记录（hook 进程写、UI 读）。 */
data class HitEntry(
    @SerializedName("ts") val ts: Long = 0,
    @SerializedName("pkg") val pkg: String = "",
    @SerializedName("what") val what: String = "",
    @SerializedName("action") val action: String = ""
)

/** 订阅索引文件（如 dy_ys10.json）的结构。 */
data class SubIndex(
    @SerializedName("full_update") val fullUpdate: FullUpdate? = null
)

data class FullUpdate(
    @SerializedName("zip_md5") val zipMd5: String? = null,
    @SerializedName("zip_url") val zipUrl: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("update_time") val updateTime: String? = null
)

/** 全局配置（app_config.txt）。 */
data class AppConfig(
    @SerializedName("beta") val beta: Boolean = false,
    @SerializedName("cnt") val cnt: Int = 0,
    @SerializedName("skip") val skip: Boolean = false,
    @SerializedName("skipMsg") val skipMsg: String? = null,
    @SerializedName("teenagers") val teenagers: Boolean = false,
    @SerializedName("pro") val pro: Map<String, Any?>? = null
)
