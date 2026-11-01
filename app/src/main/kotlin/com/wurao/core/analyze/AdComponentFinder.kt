package com.wurao.core.analyze

/**
 * 自动分析：从组件名里挖「疑似广告组件」（原型见 `tools/apk_analyze_demo.py`，实测结论见 plan 第九节之二）。
 *
 * **定位是候选推荐器，不是自动生效**。挖出来的东西要给你勾选，默认不启用 ——
 * 因为准确率约 80%，剩下 20% 里有的是 SDK 基础设施（拦了会让 SDK 异常），
 * 有的干脆不是广告（比如通知横幅）。
 *
 * 纯 Kotlin，不依赖 Android（组件名列表由调用方用 PackageManager 取），可单测。
 */
object AdComponentFinder {

    /**
     * 候选等级：
     * - [AD]：像广告展示页 / 落地页，可以直接生成"拦页面"规则
     * - [INFRA]：广告 SDK 的基础设施（文件提供者、下载服务）——**不能拦**，
     *   拦了可能让 SDK 异常甚至 App 起不来，单独标出来给你看
     */
    enum class Level { AD, INFRA }

    data class Candidate(
        val name: String,
        val level: Level,
        /** 为什么判成它（界面要显示，不让你盲选）。 */
        val reason: String,
    )

    /** 广告词：类名按驼峰拆开后命中这些词才算。 */
    private val AD_WORDS = setOf(
        "Ad", "Ads", "Advert", "Advertise", "Advertisement", "Banner",
        "Reward", "RewardVideo", "Interstitial", "NativeAd", "FeedAd",
        "Promote", "Promotion", "Splash",
    )

    /** 含 ad 字母但跟广告无关的高频词。不排除会把 Adapter/Header/Load 全判成广告。 */
    private val NOT_AD = setOf(
        "Adapter", "Address", "Add", "Added", "Loading", "Loader", "Reader",
        "Header", "Head", "Read", "Update", "Upgrade", "Advance", "Advanced",
        "Load", "Callback", "Broadcast", "Thread", "Async", "Shadow", "Trade",
    )

    /**
     * 广告 SDK 的**确切路径**。
     *
     * 踩过的坑：早期拿大厂包名前缀（比如看到 `com.bytedance` 就当 SDK）当特征，
     * 结果懂车帝自己 200 多个业务页面（IM 聊天、会员列表……）全被判成广告 ——
     * 字节系 App 的业务代码就住在 com.bytedance 下面。**只认广告 SDK 的确切路径**。
     */
    private val SDK_PATHS = listOf(
        "/openadsdk/", "pangle", "com.qq.e.", "com/qq/e/", "com.kwad",
        "com.baidu.mobads", "com.mbridge", "com.beizi.ad", "com.anythink",
        "com.sigmob", "com.unity3d.ads", "com.applovin", "com.google.android.gms.ads",
        "com.inmobi", "com.vungle", "com.tanx", "gromore", "com.kuaishou",
    )

    /** 这些路径下的 Banner 多半不是广告（通知横幅）。语境误伤要排除。 */
    private val CONTEXT_NOT_AD = listOf(".notification.", ".im.", ".chat.", ".push.")

    /**
     * @param components 组件全名（Activity / Service / Receiver / Provider）
     * @param kind 组件类型，用于区分"展示页"与"基础设施"
     */
    fun find(components: List<Pair<String, String>>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for ((kind, name) in components) {
            judge(kind, name)?.let(out::add)
        }
        return out
    }

    private fun judge(kind: String, name: String): Candidate? {
        val low = name.lowercase()
        val sdk = SDK_PATHS.firstOrNull { low.contains(it) }

        // 广告 SDK 的基础设施：不是广告页，拦了有风险 —— 单独标出来，不混进可拦清单
        if (sdk != null) {
            if (kind == "provider" || kind == "service") {
                return Candidate(name, Level.INFRA, "广告 SDK 的$kind（$sdk）—— 不是广告页，拦了有风险")
            }
            return Candidate(name, Level.AD, "命中广告 SDK 路径：$sdk")
        }

        // 语境误伤：通知/IM 里的 Banner 不是广告
        if (CONTEXT_NOT_AD.any { low.contains(it) }) return null

        val simple = name.substringAfterLast('.')
        val words = splitCamel(simple).toSet()
        val hits = words.intersect(AD_WORDS)
        if (hits.isEmpty()) return null

        // 只有噪声词命中的不算（Adapter/Header 之类）
        val noise = words.intersect(NOT_AD)
        if (noise.isNotEmpty() && hits.all { it == "Ad" || it == "Ads" }) return null
        // 单独一个 Splash 不算广告（很多 App 的启动页不叫广告）
        if (hits == setOf("Splash")) return null

        val strong = hits - setOf("Splash", "Promote", "Promotion")
        return if (strong.isNotEmpty()) {
            Candidate(name, Level.AD, "类名含 ${strong.joinToString("/")}")
        } else {
            Candidate(name, Level.AD, "类名含 ${hits.joinToString("/")}（较弱）")
        }
    }

    /** 驼峰拆词：`SplashAdActivity` → [Splash, Ad, Activity]。 */
    internal fun splitCamel(s: String): List<String> =
        Regex("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])").findAll(s).map { it.value }.toList()
}
