package com.adhammer.float

import android.view.accessibility.AccessibilityNodeInfo
import com.adhammer.rules.Cfg
import com.adhammer.util.HLog

/**
 * 八戒「自动跳过」：不写规则也能点掉开屏广告。
 *
 * 原版的做法是维护一份 3000+ 应用的「可自动跳过」白名单（订阅里的
 * ba_jie_auto_skip.txt），进应用后按文案找跳过按钮点掉。这里沿用同一份白名单，
 * 定位改为无障碍节点扫描。
 *
 * 保守策略（误点比漏点更难接受）：
 * - 只在页面刚切换的一小段时间里试，不在用户正常操作时乱点
 * - 文本长度上限 8，避免点到「跳过广告可获赠会员」这种长句
 * - 强关键词（跳过 / skip）为先；「关闭」这种弱关键词只在没有别的候选时才用
 * - 同一页面同一条文案 2 秒冷却，避免连点
 */
object AutoSkip {

    /** 强关键词：基本只会出现在广告跳过按钮上 */
    private val STRONG = listOf("跳过广告", "跳过", "skip", "skip ad", "关闭广告", "跳过 >")

    /** 弱关键词：需要额外条件才敢点 */
    private val WEAK = listOf("关闭", "close", "我知道了", "下次再说", "暂不", "以后再说")

    /** 一条命中的候选 */
    data class Hit(
        val text: String,
        val cls: String?,
        val id: String?,
        val b: List<Int>,
        val strong: Boolean,
        val node: AccessibilityNodeInfo
    )

    fun enabled(): Boolean = Cfg.get(com.adhammer.ui.UiState.local, Cfg.AUTO_SKIP, true)

    /** 这个应用要不要自动跳过：白名单模式只看生效名单（订阅 ∪ 自加 − 排除） */
    fun inScope(pkg: String): Boolean {
        if (!enabled()) return false
        if (Cfg.get(com.adhammer.ui.UiState.local, Cfg.AUTO_SKIP_ALL, false)) return true
        return pkg in com.adhammer.ui.UiState.skipScope
    }

    /** 在节点树里找最像「跳过」的那个按钮 */
    fun find(root: AccessibilityNodeInfo?): Hit? {
        if (root == null) return null
        val strong = ArrayList<Hit>()
        val weak = ArrayList<Hit>()
        collect(root, strong, weak, 0)
        return strong.minByOrNull { it.b.getOrElse(2) { 0 } } ?: weak.singleOrNull()
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        strong: MutableList<Hit>,
        weak: MutableList<Hit>,
        depth: Int
    ) {
        if (depth > 30 || strong.size + weak.size > 60) return

        val raw = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
        if (!raw.isNullOrEmpty() && raw.length <= 8) {
            val low = raw.lowercase()
            val isStrong = STRONG.any { low.contains(it) }
            val isWeak = !isStrong && WEAK.any { low == it || low == "$it。" }
            // 必须能点：自身可点，或父节点可点（很多广告把点击事件挂在容器上）
            if (isStrong || isWeak) {
                val clickableSelf = node.isClickable
                val clickableParent = runCatching { node.parent?.isClickable }.getOrNull() == true
                if (clickableSelf || clickableParent) {
                    val rect = android.graphics.Rect()
                    runCatching { node.getBoundsInScreen(rect) }
                    val hit = Hit(
                        text = raw,
                        cls = node.className?.toString(),
                        id = node.viewIdResourceName?.substringAfter("id/"),
                        b = listOf(rect.left, rect.top, rect.right, rect.bottom),
                        strong = isStrong,
                        node = node
                    )
                    if (isStrong) strong.add(hit) else weak.add(hit)
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collect(child, strong, weak, depth + 1)
        }
    }

    /** 点掉它；返回点到的文案，没点返回 null */
    fun perform(hit: Hit): String? {
        val ok = runCatching {
            hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (ok) {
            HLog.i("autoSkip click: ${hit.text}")
            return hit.text
        }
        // 自己不可点但父节点可点时，往上点一层
        val parent = runCatching { hit.node.parent }.getOrNull()
        if (parent != null) {
            val ok2 = runCatching {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (ok2) {
                HLog.i("autoSkip click(parent): ${hit.text}")
                return hit.text
            }
        }
        return null
    }
}

/**
 * 弹框拦截：原版的「取消弹框列表 / 应用升级 / 青少年模式」。
 *
 * 和 [AutoSkip] 的区别：
 * - 自动跳过只认「跳过 / skip」这类**广告**按钮，范围窄、只在开屏时试
 * - 弹框拦截认的是**用户自己维护的词表**（稍后再说 / 我知道了…），
 *   专门用来点掉运营弹窗、升级弹窗、青少年模式提示
 *
 * 为了不误点业务弹窗，这里要求：
 * - 文案长度 ≤ 10，且是「整句相等」或「以关键词结尾」（避免点到长句按钮）
 * - 必须自身可点
 * - 同一个弹框只点一次（8 秒冷却）
 */
object PopGuard {

    @Volatile
    private var cool = HashMap<String, Long>()

    fun enabled(): Boolean = Cfg.get(com.adhammer.ui.UiState.local, Cfg.POPUP, true)

    /** 当前生效的关键词：用户词表 + 两个专项开关自带的词表 */
    fun words(): Set<String> {
        val s = com.adhammer.ui.UiState.popWords.toMutableSet()
        if (Cfg.get(com.adhammer.ui.UiState.local, Cfg.POPUP_UPGRADE, true)) {
            s += com.adhammer.ui.UiState.UPGRADE_WORDS.split(Regex("\\s+"))
        }
        if (Cfg.get(com.adhammer.ui.UiState.local, Cfg.POPUP_TEEN, false)) {
            s += com.adhammer.ui.UiState.TEEN_WORDS.split(Regex("\\s+"))
        }
        return s.filter { it.isNotBlank() }.toSet()
    }

    /**
     * 在节点树里找匹配词表的按钮。
     *
     * 短词（≤3 字，如「取消」「以后」）只在**同级还存在另一个更像「确认」的按钮**时才点，
     * 避免把用户自己点的「取消」也给代劳了……实际上这类短词太危险，这里直接要求 ≥2 字
     * 且整句相等，宁可漏点。
     */
    fun find(root: AccessibilityNodeInfo?, pkg: String): Pair<String, AccessibilityNodeInfo>? {
        if (root == null || !enabled()) return null
        val ws = words()
        if (ws.isEmpty()) return null
        return walk(root, ws, pkg, 0)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        ws: Set<String>,
        pkg: String,
        depth: Int
    ): Pair<String, AccessibilityNodeInfo>? {
        if (depth > 30) return null

        val raw = node.text?.toString()?.trim()
        if (!raw.isNullOrEmpty()) {
            val low = raw.lowercase()
            if (low.length in 2..10 && node.isClickable) {
                val matched = ws.any { w ->
                    val lw = w.lowercase()
                    low == lw || low == "$lw。" || (lw.length >= 3 && low.endsWith(lw))
                }
                if (matched) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val key = "$pkg|$low"
                    if (now - (cool[key] ?: 0L) >= 8000L) {
                        cool[key] = now
                        return low to node
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = walk(child, ws, pkg, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /** 点掉它 */
    fun perform(hit: Pair<String, AccessibilityNodeInfo>): Boolean =
        runCatching { hit.second.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
}
