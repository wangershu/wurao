package com.adhammer.analyzer

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.adhammer.rules.Jsonx
import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.ViewRule
import com.adhammer.util.HLog
import dalvik.system.DexFile
import java.io.File
import java.util.zip.ZipFile

/**
 * 本地 APK 分析器 —— **不依赖大模型**的规则生产方式。
 *
 * 思路：广告 SDK 是「有限集合」，把它们的类名前缀、常用资源名、上报域名沉淀成特征库，
 * 分析 APK 时只做匹配，就能得到可解释、可勾选的候选规则。
 *
 * 四路证据：
 * 1. 组件清单 —— PackageManager 直接拿 Activity/Receiver/Service/Provider
 * 2. dex 类名 —— DexFile 枚举类名，命中广告 SDK 前缀
 * 3. 资源 id  —— 用 getIdentifier 探测 skip/close/ad_container 这类资源是否存在
 * 4. 域名     —— 扫 dex 里的字符串常量，找广告/统计上报域名
 *
 * 与 AI 的关系：AI 是「补语义」（判断某个布局是不是广告），
 * 分析器是「补事实」（这个 App 到底塞了哪些广告组件），两者产物格式一致，可叠加保存。
 */
object ApkAnalyzer {

    private const val FLAGS = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA

    enum class Cat(val label: String) {
        ACTIVITY("Activity"), RECEIVER("Receiver"), SERVICE("Service"),
        PROVIDER("Provider"), VIEW("View"), HOST("域名")
    }

    data class Candidate(
        val id: String,
        val cat: Cat,
        val title: String,
        val summary: String,
        val confidence: Int,
        val checked: Boolean,
        val ruleType: Int,
        val actionType: Int,
        val ad: String?,
        val adKey: String?,
        val host: String? = null
    )

    data class Report(
        val pkg: String = "",
        val label: String? = null,
        val version: String? = null,
        val apkPath: String? = null,
        val launcher: String? = null,
        val sdks: List<String> = emptyList(),
        val sdkSamples: List<String> = emptyList(),
        val componentCount: Int = 0,
        val dexClassCount: Int = 0,
        val candidates: List<Candidate> = emptyList(),
        val notes: List<String> = emptyList(),
        val error: String? = null
    )

    // ==================== 入口 ====================

    /** 分析已安装应用 */
    fun installed(ctx: Context, pkg: String): Report = analyze(ctx, pkg, null)

    /** 分析本地 APK 文件 */
    fun file(ctx: Context, apk: File): Report = analyze(ctx, null, apk)

    private fun analyze(ctx: Context, pkg: String?, apkFile: File?): Report {
        val notes = ArrayList<String>()
        val feats = AdFeatures.get(ctx)
        val pm = ctx.packageManager

        val info: PackageInfo? = when {
            apkFile != null -> pm.getPackageArchiveInfo(apkFile.absolutePath, FLAGS)?.also {
                it.applicationInfo?.sourceDir = apkFile.absolutePath
                it.applicationInfo?.publicSourceDir = apkFile.absolutePath
            }
            !pkg.isNullOrBlank() -> runCatching { pm.getPackageInfo(pkg, FLAGS) }.getOrNull()
            else -> null
        }
        if (info == null) {
            return Report(error = "读不到包信息：${pkg ?: apkFile?.name}")
        }

        val pkgName = info.packageName
        val src = apkFile?.absolutePath ?: info.applicationInfo?.sourceDir
        val label = runCatching {
            info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
        }.getOrNull()

        // ① launcher（首页 Activity，绝不能拦）
        val launcher = runCatching { pm.getLaunchIntentForPackage(pkgName)?.component?.className }
            .getOrNull()

        // ② dex 类名 → 广告 SDK
        val classNames = ArrayList<String>()
        if (src != null) {
            val df = runCatching { DexFile(src) }.getOrNull()
            if (df != null) {
                runCatching {
                    val e = df.entries()
                    while (e.hasMoreElements()) classNames.add(e.nextElement())
                }
                runCatching { df.close() }
            } else {
                notes.add("无法枚举 dex 类名，跳过 SDK 识别")
            }
        }

        val hitSdks = LinkedHashSet<SdkFeature>()
        val samples = ArrayList<String>()
        for (cn in classNames) {
            val sdk = feats.sdks.firstOrNull { s -> s.prefixes.any { cn.startsWith(it) } }
            if (sdk != null) {
                hitSdks.add(sdk)
                if (samples.size < 6) samples.add(cn)
            }
        }

        val candidates = ArrayList<Candidate>()

        // ③ 组件清单
        collect(info.activities, Cat.ACTIVITY, Actions.BLOCK_INTENT, pkgName, launcher, feats, hitSdks, candidates)
        collect(info.receivers, Cat.RECEIVER, Actions.BLOCK_RECEIVER, pkgName, launcher, feats, hitSdks, candidates)
        collect(info.services, Cat.SERVICE, Actions.BLOCK_SERVICE, pkgName, launcher, feats, hitSdks, candidates)
        collect(info.providers, Cat.PROVIDER, Actions.BLOCK_PROVIDER, pkgName, launcher, feats, hitSdks, candidates)

        // ④ 资源名探测（跳过按钮/广告容器）
        val res = if (src != null) runCatching { pm.getResourcesForApplication(info.applicationInfo!!) }
            .getOrNull() else null
        if (res == null) {
            notes.add("读不到资源表，跳过资源名探测（未安装 APK 常见）")
        } else {
            feats.resWords.forEach { word ->
                val id = runCatching { res.getIdentifier(word, "id", pkgName) }.getOrDefault(0)
                if (id == 0) return@forEach
                val vr = ViewRule(
                    v = "android.view.View", s = true, id = word, ht = 1, pl = 1,
                    m = "资源名探测：$word"
                )
                candidates.add(
                    Candidate(
                        id = "res:$word",
                        cat = Cat.VIEW,
                        title = word,
                        summary = "存在资源 id，可能是跳过/关闭按钮（pl=1 连带隐藏父容器）",
                        confidence = 70,
                        checked = false,
                        ruleType = RuleTypes.WU_KONG_VIEW,
                        actionType = Actions.VIEW,
                        ad = launcher ?: "*",
                        adKey = Jsonx.toJson(listOf(vr))
                    )
                )
            }
        }

        // ⑤ 域名
        if (src != null) {
            val domains = runCatching { scanDomains(src, feats) }
                .onFailure { notes.add("域名扫描失败：${it.message}") }
                .getOrDefault(emptyList())
            domains.forEach { d ->
                candidates.add(
                    Candidate(
                        id = "host:$d",
                        cat = Cat.HOST,
                        title = d,
                        summary = "dex 字符串里的疑似广告/统计域名，交给沙僧拦截",
                        confidence = 55,
                        checked = false,
                        ruleType = 0,
                        actionType = 0,
                        ad = null,
                        adKey = null,
                        host = d
                    )
                )
            }
        }

        HLog.i("analyze $pkgName: sdks=${hitSdks.size} candidates=${candidates.size} classes=${classNames.size}")
        return Report(
            pkg = pkgName,
            label = label,
            version = info.versionName,
            apkPath = src,
            launcher = launcher,
            sdks = hitSdks.map { it.name },
            sdkSamples = samples,
            componentCount = (info.activities?.size ?: 0) + (info.receivers?.size ?: 0) +
                    (info.services?.size ?: 0) + (info.providers?.size ?: 0),
            dexClassCount = classNames.size,
            candidates = candidates.sortedByDescending { it.confidence },
            notes = notes
        )
    }

    // ==================== 组件 → 候选 ====================

    private fun collect(
        arr: Array<out ComponentInfo>?,
        cat: Cat,
        actionType: Int,
        pkgName: String,
        launcher: String?,
        feats: FeaturePack,
        hitSdks: Set<SdkFeature>,
        out: ArrayList<Candidate>
    ) {
        arr?.forEach { ci ->
            val full = if (ci.name.startsWith(".")) pkgName + ci.name else ci.name
            if (full == launcher) return@forEach          // 首页绝不动
            val simple = full.substringAfterLast('.')

            val sdk = feats.sdks.firstOrNull { s -> s.prefixes.any { full.startsWith(it) } }
                ?: if (hitSdks.isNotEmpty()) {
                    // 类名不在 SDK 包名下，但整个 App 命中了 SDK 且名字像广告
                    val wordHit = feats.activityWords.firstOrNull { simple.contains(it, true) }
                    if (wordHit != null) {
                        feats.sdks.firstOrNull { s ->
                            s.prefixes.any { p -> full.startsWith(p.substringBeforeLast('.', p)) }
                        } ?: hitSdks.firstOrNull()
                    } else null
                } else null

            val wordHit = feats.activityWords.firstOrNull { simple.contains(it, true) }
            val safeHit = feats.safeWords.firstOrNull { simple.contains(it, true) }

            val confidence = when {
                sdk != null && wordHit != null -> 92
                sdk != null -> 85
                wordHit != null -> 55
                else -> 0
            }
            if (confidence == 0) return@forEach
            // 名字像主界面又没命中 SDK → 放弃，避免把 App 搞崩
            if (safeHit != null && sdk == null) return@forEach

            val summary = buildString {
                if (sdk != null) append("命中 ${sdk.name}") else append("名称含「$wordHit」")
                if (safeHit != null) append(" · 注意名称含「$safeHit」，请确认不是主界面")
                append(" · ${describe(actionType)}")
            }

            // Activity 用防火墙拦截（不改组件状态，最安全）；其余组件直接禁用
            val defaultOn = when (cat) {
                Cat.ACTIVITY -> sdk != null && safeHit == null
                Cat.RECEIVER -> true
                Cat.SERVICE -> true
                Cat.PROVIDER -> true
                else -> false
            }

            out.add(
                Candidate(
                    id = "${cat.name}:$full",
                    cat = cat,
                    title = simple,
                    summary = summary,
                    confidence = confidence,
                    checked = defaultOn,
                    ruleType = RuleTypes.WU_KONG_COMPONENT,
                    actionType = actionType,
                    ad = full,
                    adKey = null
                )
            )
        }
    }

    private fun describe(actionType: Int): String = when (actionType) {
        Actions.BLOCK_INTENT -> "拦截启动（不动组件状态，最安全）"
        Actions.BLOCK_RECEIVER -> "禁用广播接收器"
        Actions.BLOCK_SERVICE -> "禁用服务（可能影响推送）"
        Actions.BLOCK_PROVIDER -> "禁用内容提供者"
        else -> Actions.describe(actionType)
    }

    // ==================== 域名扫描 ====================

    private val DOMAIN_RE =
        Regex("[a-z0-9][a-z0-9._-]{2,40}\\.(com|cn|net|org|io|xyz|top|online|co|me|info|biz|tv|cc|link|cloud|app)(?![a-z0-9])")

    private fun scanDomains(apkPath: String, feats: FeaturePack, limit: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        ZipFile(apkPath).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory || !e.name.endsWith(".dex")) continue
                if (e.size > 32L * 1024 * 1024) continue
                val bytes = runCatching { zf.getInputStream(e).readBytes() }.getOrNull() ?: continue
                // 逐字节当 ASCII 看：dex 的字符串常量池是连续字节，够用且比解析 dex 便宜得多
                val text = bytes.toString(Charsets.ISO_8859_1)
                for (m in DOMAIN_RE.findAll(text)) {
                    val d = m.value.trim('.', '-', '_')
                    if (d.length < 6 || d.length > 60) continue
                    if (isAdDomain(d, feats)) out.add(d)
                    if (out.size >= limit) return out.toList()
                }
            }
        }
        return out.toList()
    }

    private fun isAdDomain(d: String, feats: FeaturePack): Boolean {
        if (feats.domainExclude.any { d.contains(it, true) }) return false
        return feats.domainWords.any { w -> d.contains(w, true) }
    }

    // ==================== 候选 → 规则 ====================

    /** 勾选的候选转成规则包 + 域名列表 */
    fun toRules(pkg: String, picks: List<Candidate>): Pair<List<RulePack>, List<String>> {
        val byType = HashMap<Int, MutableList<RuleItem>>()
        val hosts = ArrayList<String>()

        picks.forEach { c ->
            if (c.cat == Cat.HOST) {
                c.host?.let { hosts.add(it) }
                return@forEach
            }
            byType.getOrPut(c.ruleType) { ArrayList() }.add(
                RuleItem(
                    actionType = c.actionType,
                    ad = c.ad,
                    adKey = c.adKey,
                    isEnable = true,
                    ruleType = c.ruleType,
                    remark = "APK 分析：${c.summary.substringBefore(" · ")}"
                )
            )
        }

        val packs = byType.map { (type, items) ->
            RulePack(
                isEnable = true,
                name = "APK 分析",
                pkg = pkg,
                ruleType = type,
                rules = items
            )
        }
        return packs to hosts
    }
}
