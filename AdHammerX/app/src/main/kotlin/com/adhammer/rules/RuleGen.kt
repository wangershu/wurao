package com.adhammer.rules

import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.StepAct
import com.adhammer.rules.model.StepCt
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.Step
import com.adhammer.rules.model.Task
import com.adhammer.rules.model.ViewRule
import com.adhammer.util.HLog

/**
 * 规则生成器：把「布局快照」变成可保存的规则。
 *
 * 两条路：
 * 1. [heuristic] —— 本地启发式，不联网。按「跳过 / 关闭 / 广告」等文案与
 *    资源名关键词定位广告控件，产出悟空 View 规则 + 八戒点击任务。
 * 2. com.adhammer.ai.AiRuleGen —— 把同一份快照交给大模型，返回同样 schema 的 JSON。
 *
 * 两者产物结构一致，都能直接进 [RuleStore]，所以 AI 只是「更好的启发式」，
 * 即便没填 API Key 也照样能造规则。
 */
object RuleGen {

    /** 快照里的一行 = 一个 View */
    data class Node(
        val depth: Int,
        val cls: String,
        val id: String?,
        val text: String?,
        val clickable: Boolean,
        /** b= 后面的 left,top,right,bottom；没有就是空 */
        val bounds: List<Int> = emptyList()
    )

    /** 结果：悟空规则包 + 八戒任务（两者分别落到不同的 key） */
    data class GenResult(
        val packs: List<RulePack>,
        val tasks: List<Task>,
        val act: String?
    )

    // 文案关键词：命中即认为是「跳过广告」类控件
    private val TEXT_WORDS = listOf(
        "跳过", "跳过广告", "跳过 >", "关闭", "关闭广告", "忽略", "退出",
        "skip", "Skip", "SKIP", "close", "Close", "CLOSE", "ad", "AD"
    )

    // 资源名关键词：没有文案时靠 id 兜底
    private val ID_WORDS = listOf(
        "skip", "close", "ad_", "_ad", "advert", "splash", "jump", "count", "timer", "dismiss"
    )

    private val ID_RE = Regex("id=(\\S+)")
    private val TXT_RE = Regex("txt=\"(.*?)\"")
    private val B_RE = Regex("b=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)")

    /** 解析快照文本 → View 节点列表 */
    fun parse(snapshot: String): List<Node> {
        val out = ArrayList<Node>()
        snapshot.lineSequence().forEach { raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@forEach
            val indent = raw.takeWhile { it == ' ' }.length
            var body = raw.trimStart()
            val clickable = body.endsWith("[clickable]")
            if (clickable) body = body.removeSuffix("[clickable]").trim()
            if (body.isBlank()) return@forEach
            val cls = body.substringBefore(' ').ifBlank { body }
            val id = ID_RE.find(body)?.groupValues?.getOrNull(1)
            val text = TXT_RE.find(body)?.groupValues?.getOrNull(1)
            val bounds = B_RE.find(body)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() } ?: emptyList()
            out.add(Node(indent / 2, cls, id, text, clickable, bounds))
        }
        return out
    }

    /** 从快照头部取 (activity, package)；快照由 float.Snapshot 生成 */
    fun pageOf(snapshot: String): Pair<String?, String?> {
        val lines = snapshot.lineSequence().toList()
        val act = lines.firstOrNull { it.startsWith("# page:") }
            ?.removePrefix("# page:")?.trim()?.takeIf { it.isNotBlank() }
        val pkg = lines.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("#") }
        return act to pkg
    }

    /**
     * 本地启发式生成规则。
     *
     * 策略（保守优先）：
     * - 优先取「有文案且可点击」的控件，文案命中跳过/关闭等关键词
     * - 其次取 id 命中广告关键词的控件
     * - 每个候选产出：
     *   ① 悟空 View 规则（actionType=8，pl=1 连带隐藏父容器）
     *   ② 八戒点击任务（at=8，按文本/类名定位后点击）
     */
    fun heuristic(pkg: String, snapshot: String): GenResult {
        val (act, snapPkg) = pageOf(snapshot)
        val target = pkg.ifBlank { snapPkg ?: "" }
        if (target.isBlank()) return GenResult(emptyList(), emptyList(), act)

        val nodes = parse(snapshot)
        if (nodes.isEmpty()) return GenResult(emptyList(), emptyList(), act)

        val candidates = nodes.filter { it.clickable }.mapNotNull { node ->
            val textHit = node.text?.let { t -> TEXT_WORDS.firstOrNull { t.contains(it) } }
            val idHit = node.id?.let { i -> ID_WORDS.firstOrNull { i.contains(it, true) } }
            when {
                textHit != null -> node to "文案命中：$textHit"
                idHit != null -> node to "资源名命中：$idHit"
                else -> null
            }
        }.distinctBy { (n, _) -> "${n.cls}|${n.id}|${n.text}" }
            .take(6)

        if (candidates.isEmpty()) return GenResult(emptyList(), emptyList(), act)

        val items = ArrayList<RuleItem>()
        val steps = ArrayList<Step>()

        candidates.forEach { (node, why) ->
            val resName = node.id?.substringAfter('/')
            val viewRule = ViewRule(
                v = node.cls,
                s = true,
                id = resName,
                t = node.text,
                ht = 1,
                pl = 1,          // 向外扩一层，隐藏整个广告容器
                m = why
            )
            items.add(
                RuleItem(
                    actionType = Actions.VIEW,
                    // 拿不到 Activity 名时退化成包名，Engine 会当成「所有页面生效」
                    ad = act?.ifBlank { null } ?: target,
                    adKey = Jsonx.toJson(listOf(viewRule)),
                    isEnable = true,
                    ruleType = RuleTypes.WU_KONG_VIEW,
                    remark = why
                )
            )
            // ct 必须和填了什么对得上，否则执行器按错误的定位方式去找，步骤会被判成不可执行
            val ct = when {
                !node.text.isNullOrBlank() -> if (node.cls.isNotBlank()) StepCt.BY_CLASS else StepCt.BY_TEXT
                node.id.isNullOrBlank() -> StepCt.BY_CLASS
                else -> StepCt.BY_ID
            }
            steps.add(
                Step(
                    at = StepAct.CLICK,
                    ct = ct,
                    wv = node.cls,
                    t = node.text,
                    vId = node.id,
                    // 记下 bounds，换机型时按相对比例换算坐标（见 BaJieService.resolvePoint）
                    leftX = node.bounds.getOrElse(0) { -1 },
                    topY = node.bounds.getOrElse(1) { -1 },
                    rightX = node.bounds.getOrElse(2) { -1 },
                    bottomY = node.bounds.getOrElse(3) { -1 },
                    pRel = node.bounds.size == 4,
                    x = if (node.bounds.size == 4) (node.bounds[0] + node.bounds[2]) / 2 else -1,
                    y = if (node.bounds.size == 4) (node.bounds[1] + node.bounds[3]) / 2 else -1,
                    c = 1,
                    d = 300
                )
            )
        }

        val pack = RulePack(
            isEnable = true,
            name = "本地生成",
            pkg = target,
            ruleType = RuleTypes.WU_KONG_VIEW,
            rules = items
        )
        val task = Task(
            act = act,
            isEnable = true,
            name = "本地生成-跳过广告",
            pkg = target,
            ruleType = RuleTypes.BA_JIE_TASK,
            steps = steps
        )
        HLog.i("heuristic gen: ${items.size} rules for $target")
        return GenResult(listOf(pack), listOf(task), act)
    }

    /** 把 AI 返回的 JSON 解析成规则包；结构不对就返回 null */
    fun fromAiJson(json: String?): RulePack? {
        if (json.isNullOrBlank()) return null
        return runCatching { Jsonx.gson.fromJson(json, RulePack::class.java) }
            .onFailure { HLog.w("ai json parse failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.rules.isNotEmpty() }
    }
}
