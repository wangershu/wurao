package com.wurao.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height
import top.yukonga.miuix.kmp.basic.IconButton
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wurao.core.model.Act
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.assist.SnapshotService
import com.wurao.core.Cfg
import com.wurao.core.source.GkdSubscription
import com.wurao.core.store.RuleStore
import com.wurao.core.source.HostList
import com.wurao.core.RiskGate
import com.wurao.core.analyze.AdComponentFinder
import com.wurao.core.analyze.RuleGen

/**
 * 界面。**信息架构沿用上一版**：底部三个页签（看板 / 应用 / 设置）。
 *
 * 视觉组件先用 material3 把骨架跑通；上一版用的是 miuix（MIUI 风格），
 * 骨架稳定后再换回去 —— 换的是组件库，页签与层级不变。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 把落盘的命中记录读回内存（这些记录是目标进程通过 HitProvider 写进来的）
        HitProvider.restore(this)
        // 同步加载，不再放后台 —— 后台会和推送抢：加载没完就推送，
        // 会把"半成品"写回去覆盖完整配置，实测静默丢了 6700 条规则。
        // 2.8 万条实测不卡（ANR 记录新增 0），为它冒竞态的险不值。
        restoreRules()
        rulesLoaded = true
        // 绑上框架服务后会把本地配置推进 hook 进程；之后每次改动也会推
        CfgPush.bind(this)

        // 调试直达：adb shell am start ... --es add_pkg <包名> --es add_sel_b64 <base64选择器> [--es add_act hide]
        // 省得每次验证都要在界面上点半天（上一版也留了这类入口）。
        if (handleDirect(intent)) {
            finish()
            return
        }

        // miuix 组件要从 MiuixTheme 取主题（CompositionLocal），
        // 不包的话一打开就崩 —— 之前就是这么栽的
        floatingBar = prefs().getBoolean("floating_bar", true)
        // 主题跟随系统（miuix 默认自动跟随），不单独给深色/浅色开关
        setContent { MiuixTheme { WuraoApp() } }
    }

    /**
     * 界面已经开着时，am start 走的是 onNewIntent 而不是 onCreate ——
     * 只写在 onCreate 里的话，调试直达会"看起来没反应"。
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (handleDirect(intent)) finish()
    }

    /** @return true 表示这条 intent 是调试直达，已经处理完（调用方应 finish） */
    /** 调试直达要打开的详情页包名（--es open_pkg） */
    /** 应用页的搜索词。放 Activity 层：列表作用域（LazyListScope）里不能用 remember。 */
    private var appQuery by mutableStateOf("")

    /** 应用页：只看拦过的应用（282 个里只有两三个动过手，其余翻起来是噪音）。 */
    private var appOnlyHit by mutableStateOf(false)

    /** 底栏悬浮还是贴底（旧版外观设置里的一项）。 */
    private var floatingBar by mutableStateOf(true)

    /** 主题：跟随系统 / 浅色 / 深色。 */
    private var themeMode by mutableStateOf("system")

    /** 设置页的二级页：appearance（外观）/ about（关于）。 */
    private var subPage by mutableStateOf<String?>(null)

    /** 调试直达要打开的详情页包名（--es open_pkg）。 */
    private var initialPkg: String? = null

    /**
     * 规则库是否已在后台加载完。**放后台是必须的**：几万条规则在主线程解析会 ANR
     * （现在 2.8 万条实测不卡，但到十万条量级就会 —— 提前按这个量级做）。
     */
    private var rulesLoaded by mutableStateOf(false)

    /**
     * 当前页签。**放在 Activity 层**：调试直达要能直接切页，
     * 若只放在 composable 的 remember 里，界面已经运行时直达改的只是初始值，切不动。
     */
    private var tabState by mutableStateOf(0)

    /** 调试直达要落到哪个页签（--es tab 2）；adb shell 没有 INJECT_EVENTS，
        没法用 input tap 点页签，只能走这条路 */

    private fun handleDirect(intent: android.content.Intent?): Boolean {
        // 直接进某个应用的详情页，省得每次点半天
        // 抓屏：置上标记，然后切到目标 App（adb 点不了按钮，只能这样触发）
        intent?.getStringExtra("capture")?.let {
            val ok = SnapshotService.active
            SnapshotService.wantPkg = intent?.getStringExtra("capture_pkg")
            SnapshotService.wantCapture = true
            android.util.Log.i("Wurao", "抓屏：服务=${if (ok) "已连接" else "未连接"}，已置标记，请切到目标 App")
            finish()
            return true
        }
        // 打印抓到的控件（adb 看不到长列表，只能打日志）
        intent?.getStringExtra("picks")?.let {
            val list = SnapshotService.lastSnapshot
            android.util.Log.i("Wurao", "抓到 ${list?.size ?: 0} 个控件（${SnapshotService.lastPkg}）")
            list?.take(8)?.forEach { pick ->
                val n = pick.node
                android.util.Log.i(
                    "Wurao",
                    "  ${n.className.substringAfterLast('.')} | text=${n.text} | desc=${n.desc} | vid=${n.vid} | 容器=${pick.container?.vid}"
                )
            }
            finish()
            return true
        }
        // 风险级开关直达：--es risk <包名> --es kinds COMPONENT,NET,SDK,SENSOR
        // 验完用 --es risk_clear <包名> 关回去（这些能力默认必须全关）
        // 往某个 App 的拦截名单里加一个组件：--es add_component <包名> --es component <类名>
        intent?.getStringExtra("add_component")?.let { pkg ->
            val comp = intent?.getStringExtra("component")?.trim()
            if (comp.isNullOrBlank()) {
                finish()
                return true
            }
            val key = Cfg.componentsKey(pkg)
            val old = Cfg.decodeList(prefs().getString(key, null)).toMutableSet()
            old += comp
            prefs().edit().putString(key, Cfg.encodeList(old)).apply()
            CfgPush.flush(this)
            android.util.Log.i("Wurao", "组件拦截[$pkg] 共 ${old.size} 个，新加: $comp")
            finish()
            return true
        }
        intent?.getStringExtra("risk")?.let { pkg ->
            val kinds = Cfg.decodeKinds(intent?.getStringExtra("kinds"))
            prefs().edit().putString(Cfg.riskKey(pkg), Cfg.encodeKinds(kinds)).apply()
            CfgPush.flush(this)
            android.util.Log.i("Wurao", "风险级[$pkg] = ${Cfg.encodeKinds(kinds)}")
            finish()
            return true
        }
        intent?.getStringExtra("risk_clear")?.let { pkg ->
            prefs().edit().remove(Cfg.riskKey(pkg)).apply()
            CfgPush.flush(this)
            android.util.Log.i("Wurao", "风险级[$pkg] 已清空")
            finish()
            return true
        }
        // 打印作用域与框架信息：判断"某个 App 为什么没生效"先看这个
        intent?.getStringExtra("scope")?.let {
            val list = CfgPush.scope()
            android.util.Log.i("Wurao", "框架: ${CfgPush.framework()}")
            android.util.Log.i("Wurao", "作用域 ${list.size} 个: " + list.take(30).joinToString(","))
            android.util.Log.i(
                "Wurao",
                "网易云在作用域里吗: ${list.contains("com.netease.cloudmusic")}；" +
                    "美团: ${list.contains("com.sankuai.meituan")}"
            )
            finish()
            return true
        }
        intent?.getStringExtra("import_url")?.let { url ->
            android.util.Log.i("Wurao", "开始从网址导入: $url")
            importSourceUrl(url) { report ->
                android.util.Log.i("Wurao", "import_url: $report")
                android.widget.Toast.makeText(this, report, 1).show()
                finish()
            }
            return false
        }
        intent?.getStringExtra("export")?.let {
            val text = exportText()
            android.util.Log.i("Wurao", "export: ${text.length} 字符 :: " + text.take(160))
            finish()
            return true
        }
        // 导入一律**放后台**：一万多条域名在主线程解析+入库+推送会把界面卡死（实测 ANR）。
        // 这里是调试入口，更要守这条 —— 否则"导入大表"直接把模块搞成无响应。
        // 抓屏链路的后半段：用抓到的第 N 个控件生成规则并保存
        // （adb 点不了列表，只能给序号）
        // 删掉某条规则（按选择器精确匹配）—— 验证产物不能留在库里，
        // 否则会真的藏掉人家首页上的按钮
        intent?.getStringExtra("del_rule")?.let { sel ->
            val hit = RuleStore.all().filter { it.target == sel }
            hit.forEach { RuleStore.remove(it.id) }
            pushRulesAsync()
            android.util.Log.i("Wurao", "del_rule: 删除 ${hit.size} 条（target=$sel）")
            finish()
            return true
        }
        // 对某个 App 跑自动分析（扫它的组件挖广告候选）—— 本地扫 APK，
        // 不需要目标进程被 hook，所以作用域没加也能跑
        intent?.getStringExtra("analyze")?.let { pkg ->
            runInBackground {
                val pm = packageManager
                val pi = runCatching {
                    pm.getPackageInfo(
                        pkg,
                        android.content.pm.PackageManager.GET_ACTIVITIES or
                            android.content.pm.PackageManager.GET_SERVICES or
                            android.content.pm.PackageManager.GET_RECEIVERS or
                            android.content.pm.PackageManager.GET_PROVIDERS
                    )
                }.getOrNull()
                if (pi == null) return@runInBackground "分析失败：拿不到 $pkg 的组件（装了吗？）"
                val comps = ArrayList<Pair<String, String>>()
                pi.activities?.forEach { comps += it.name to "activity" }
                pi.services?.forEach { comps += it.name to "service" }
                pi.receivers?.forEach { comps += it.name to "receiver" }
                pi.providers?.forEach { comps += it.name to "provider" }
                val cands = AdComponentFinder.find(comps)
                if (cands.isEmpty()) return@runInBackground "$pkg：扫了 ${comps.size} 个组件，没挖到候选"
                val head = cands.take(12).joinToString("\n") { c ->
                    "  [${c.level}] ${c.name.substringAfterLast('.')} —— ${c.reason}"
                }
                "$pkg：组件 ${comps.size} 个，候选 ${cands.size} 条，前 ${minOf(12, cands.size)} 条：\n$head"
            }
            return false
        }
        // 把抓到的控件框在界面上（需要抓屏服务开着）：--es frames 1
        intent?.getStringExtra("frames")?.let {
            val svc = SnapshotService.instance
            if (svc == null) {
                android.util.Log.i("Wurao", "frames: 抓屏服务没连上")
                finish()
                return true
            }
            val picks = SnapshotService.lastSnapshot
            if (picks.isNullOrEmpty()) {
                android.util.Log.i("Wurao", "frames: 还没有抓到控件（先抓屏）")
                finish()
                return true
            }
            val ok = com.wurao.assist.FrameOverlay.show(svc, picks) { pick ->
                val rules = RuleGen.rules(pick.pkg ?: "", pick.node, pick.container, act = Act.HIDE)
                rules.forEach { RuleStore.upsert(it) }
                pushRulesAsync()
                android.widget.Toast.makeText(
                    svc,
                    "已保存 ${rules.size} 条：${rules.firstOrNull()?.target}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                com.wurao.assist.FrameOverlay.hide(svc)
            }
            android.util.Log.i("Wurao", "frames: 显示 ${picks.size} 个框 ok=$ok")
            finish()
            return true
        }
        intent?.getStringExtra("gen_rule")?.let { idxStr ->
            val idx = idxStr.toIntOrNull() ?: 0
            val pick = SnapshotService.lastSnapshot?.getOrNull(idx)
            if (pick == null) {
                android.util.Log.i("Wurao", "gen_rule: 没有第 $idx 个控件（先抓屏）")
                finish()
                return true
            }
            val pkg = pick.pkg ?: ""
            val rules = RuleGen.rules(pkg, pick.node, pick.container, act = Act.HIDE)
            rules.forEach { RuleStore.upsert(it) }
            pushRulesAsync()
            android.util.Log.i(
                "Wurao",
                "gen_rule: 由「${pick.node.text ?: pick.node.desc ?: pick.node.vid}」生成 ${rules.size} 条，" +
                    "第一条 = ${rules.firstOrNull()?.target}"
            )
            finish()
            return true
        }
        intent?.getStringExtra("import_file")?.let { path ->
            runInBackground {
                // 不以 / 开头就当文件名，读 App 自己的目录（filesDir）——
                // 硬编码 /data/data/... 在不同机型/多用户下可能不对，而且外部路径往往没权限读
                val file = if (path.startsWith("/")) java.io.File(path)
                else java.io.File(filesDir, path)
                android.util.Log.i("Wurao", "读文件: ${file.absolutePath} 存在=${file.exists()} 可读=${file.canRead()}")
                val body = runCatching { file.readText() }.getOrNull()
                if (body.isNullOrBlank()) "读不到文件：${file.absolutePath}" else importSource(body)
            }
            return false
        }
        intent?.getStringExtra("import_sub_b64")?.let {
            val raw = runCatching {
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
            }.getOrNull()
            if (raw.isNullOrBlank()) {
                finish()
                return true
            }
            runInBackground { importSource(raw) }
            return false
        }
        intent?.getStringExtra("tab")?.toIntOrNull()?.let {
            tabState = it
            return false
        }
        intent?.getStringExtra("open_pkg")?.takeIf { it.isNotBlank() }?.let {
            initialPkg = it
            return false
        }
        val addPkg = intent?.getStringExtra("add_pkg")
        // 选择器里有 `<<`，直接用 --es 传会被 adb shell 当成 here-document 吃掉
        // （报错：here document unclosed），所以额外支持 base64 形式
        val addSel = intent?.getStringExtra("add_sel")
            ?: intent?.getStringExtra("add_sel_b64")?.let {
                runCatching {
                    String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                }.getOrNull()
            }
        if (!addPkg.isNullOrBlank() && !addSel.isNullOrBlank()) {
            val actKey = intent?.getStringExtra("add_act") ?: Act.HIDE.key
            val act = Act.fromKey(actKey) ?: Act.HIDE
            val r = Rule(
                domain = com.wurao.core.model.Domain.NODE,
                target = addSel,
                act = act,
                scope = if (addPkg == "*") Scope.Global else Scope.App(addPkg),
                origin = Origin.User,
                groupKey = Cfg.GROUP_USER,
                ruleKey = addSel.hashCode().toString(),
                note = addSel,
            )
            if (r.validate().isEmpty()) {
                // 必须写规则库再推送 —— 之前这里写的是旧的 user_rules 键，
                // 而统一后读的是 rules 键，于是"加了规则却看不见"（静默失效）
                RuleStore.upsert(r)
                pushRulesAsync()
                val ok = true
                android.util.Log.i("Wurao", "addRule ok=$ok pkg=$addPkg act=${act.key}")
                android.widget.Toast.makeText(this, "已加规则 ok=$ok：${act.key} $addPkg", 1).show()
            } else {
                android.util.Log.w("Wurao", "addRule 非法：${r.validate().joinToString()}")
                android.widget.Toast.makeText(this, "规则不合法：${r.validate().joinToString()}", 1).show()
            }
            return true
        }
        return false
    }

        /**
     * 导入一份源：认得出是 GKD 订阅还是域名表，解析后存进订阅那一份。
     * **不静默**：跳过了多少、为什么跳过，都要写进报告给你看。
     */
    fun doImport(text: String): String {
        // 认类型的依据必须是"长什么样"，不能是"含不含某个词" ——
        // 之前写的是 contains("apps")，而域名里常有 apps.xxx.com，
        // 于是域名表被误判成 GKD 订阅拿去当 JSON5 解析，整片静默报废
        // （表现就是"导入了但只进了一半"，且不报错）
        val firstLine = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") } ?: ""
        val looksLikeSub = firstLine.startsWith("{") || firstLine.startsWith("[{")
        val rules: List<Rule>
        val head: String
        if (looksLikeSub) {
        val r = GkdSubscription.parse(text)
        rules = r.rules
        val reasons = r.skipped.take(3).joinToString("；") { it.reason }
        head = "订阅共 ${r.total} 条：采用 ${rules.size}，跳过 ${r.skipped.size}" +
            (if (r.skipped.isNotEmpty()) "（例如：$reasons）" else "")
        } else {
        val h = HostList.parse(text)
        rules = h.hosts.map { domain ->
            Rule(
                domain = com.wurao.core.model.Domain.HOST,
                target = domain,
                act = Act.BLOCK,
                scope = Scope.Global,
                origin = Origin.Subscription(0),
                groupKey = "hosts",
                ruleKey = domain.hashCode().toString(),
                note = "广告域名",
            )
        }
        head = "域名表：认出 ${rules.size} 个域名" +
            (if (h.ignoredLines > 0) "，看不懂的行 ${h.ignoredLines} 行" else "")
        }
        prefs().edit().putString(Cfg.KEY_SUB_RULES, Cfg.encodeRules(rules)).apply()
        CfgPush.flush(this@MainActivity)
        return head
    }

    /**
     * 导入一份源：认得出是 GKD 订阅还是域名表，解析后存进订阅那一份。
     *
     * 两条规矩：
     * - **不静默**：跳过了多少、为什么跳过，都要写进报告给你看；
     * - 只 replace 订阅那一份，**不动你自己抓的规则**。
     */
    private fun importSource(text: String): String {
        // 第三种源：内部规则文本（tools/ 里的转换脚本产出的格式）。
        // 用来把旧规则库的结论搬进来 —— 只搬名单，旧格式的解析代码不进 App。
        val looksLikeInternal = text.contains("\t") && text.lineSequence().firstOrNull()
            ?.split("\t")?.getOrNull(1)?.let { Act.fromKey(it.trim()) != null } == true
        if (looksLikeInternal) {
            val list = Cfg.decodeRules(text)
            list.forEach { RuleStore.upsert(it) }
            pushRulesAsync()
            return "规则文本：读入 ${list.size} 条；库里现在共 ${RuleStore.all().size} 条"
        }

        // 认类型的依据必须是"长什么样"，不能是"含不含某个词" ——
        // 之前写的是 contains("apps")，而域名里常有 apps.xxx.com，
        // 于是域名表被误判成 GKD 订阅拿去当 JSON5 解析，整片静默报废
        // （表现就是"导入了但只进了一半"，且不报错）
        val firstLine = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") } ?: ""
        val looksLikeSub = firstLine.startsWith("{") || firstLine.startsWith("[{")
        val rules: List<Rule>
        val head: String
        if (looksLikeSub) {
            val r = GkdSubscription.parse(text)
            val report = RuleStore.import(r)
            val reasons = r.skipped.take(3).joinToString("；") { it.reason }
            head = "订阅共 ${r.total} 条：采用 ${report.added}，跳过 ${report.skipped}" +
                (if (r.skipped.isNotEmpty()) "（例如：$reasons）" else "") +
                "；库里现在共 ${RuleStore.all().size} 条"
        } else {
            val h = com.wurao.core.net.DomainSet.parse(text)
            h.forEach { domain ->
                RuleStore.upsert(Rule(
                    domain = com.wurao.core.model.Domain.HOST,
                    target = domain,
                    act = Act.BLOCK,
                    scope = Scope.Global,
                    origin = Origin.Subscription(0),
                    groupKey = "hosts",
                    ruleKey = domain.hashCode().toString(),
                    note = "广告域名",
                ))
            }
            pushRulesAsync()
            head = "域名表：读入 ${h.size} 个域名（已去重）；库里现在共 ${RuleStore.all().size} 条"
        }
        pushRulesAsync()
        return head
    }

    /**
     * 备份成一段文本（所有配置的键值对）。
     *
     * 用文本而不是导出文件：不用申请存储权限，你复制出来贴到任何地方都行。
     */
    private fun exportText(): String {
        val o = org.json.JSONObject()
        prefs().all.forEach { (k, v) -> o.put(k, v?.toString() ?: "") }
        return o.toString()
    }

    /** 从备份文本恢复。整份覆盖 —— 所以恢复前会先告诉你里面有多少项。 */
    private fun restoreText(text: String): String {
        val o = runCatching { org.json.JSONObject(text) }.getOrElse {
            return "这段不是有效的备份（解析失败）"
        }
        val e = prefs().edit()
        var n = 0
        o.keys().forEach { k ->
            e.putString(k, o.optString(k))
            n++
        }
        e.apply()
        CfgPush.flush(this)
        return "已恢复 $n 项配置"
    }

    /**
     * 把落盘的规则读回规则库。
     *
     * 第一次还会把旧的两份（user_rules / sub_rules）迁进来 ——
     * 你已经配好的规则不能因为这次统一而丢掉。
     */
    private fun restoreRules() {
        val unified = Cfg.decodeRules(prefs().getString(Cfg.KEY_RULES, null))
        if (unified.isNotEmpty()) {
            unified.forEach { RuleStore.upsert(it) }
            return
        }
        val legacy = Cfg.decodeRules(prefs().getString(Cfg.KEY_USER_RULES, null)) +
            Cfg.decodeRules(prefs().getString(Cfg.KEY_SUB_RULES, null))
        if (legacy.isEmpty()) return
        legacy.forEach { RuleStore.upsert(it) }
        pushRulesAsync()
        android.util.Log.i("Wurao", "规则迁移：${legacy.size} 条 -> 统一键")
    }

    /**
     * 规则有任何改动都要调一次：写统一键并推送到 hook 进程。
     *
     * **放后台**：几万条规则时，编码 + 写配置 + 推文件这一串在主线程是好几秒的活，
     * 实测就是这么 ANR 的（主线程被卡住 5 秒以上）。调用方若要条数，走 [pushRulesAsync]。
     */
    private fun pushRulesAsync() {
        Thread {
            val all = RuleStore.all()
            prefs().edit().putString(Cfg.KEY_RULES, Cfg.encodeRules(all)).apply()
            CfgPush.flush(this)
        }.start()
    }

    /** 后台推送，并把"库里现在多少条"交给回调（回主线程）。 */
    private fun pushRulesAsync(onCount: (Int) -> Unit) {
        Thread {
            val all = RuleStore.all()
            prefs().edit().putString(Cfg.KEY_RULES, Cfg.encodeRules(all)).apply()
            CfgPush.flush(this)
            val n = all.size
            runOnUiThread { onCount(n) }
        }.start()
    }

    /**
     * 从网址导入：下载与解析都放后台线程 —— 订阅动辄 1MB，在 UI 线程解析会卡住界面。
     */
    /**
     * 跑到后台线程再回主线程报结果。导入类操作都走它 ——
     * 主线程做这种量级的活会 ANR（实测导入一万多条域名时就是这么卡的）。
     */
    private fun runInBackground(work: () -> String) {
        Thread {
            val report = runCatching { work() }.onFailure {
                android.util.Log.w("Wurao", "导入异常: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault("导入失败")
            runOnUiThread {
                android.util.Log.i("Wurao", "import: $report")
                android.widget.Toast.makeText(this, report, 1).show()
                finish()
            }
        }.start()
    }

    private fun importSourceUrl(url: String, done: (String) -> Unit) {
        Thread {
            val started = System.currentTimeMillis()
            // 必须设超时：不设的话网络不通时会一直挂着，看起来就是「点了没反应」
            val result = runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 15_000
                conn.readTimeout = 25_000
                conn.getInputStream().bufferedReader().readText()
            }
            val report = result.fold(
                onSuccess = { body ->
                    if (body.isBlank()) {
                        "下载内容为空"
                    } else {
                        val r = importSource(body)
                        "$r；耗时 ${System.currentTimeMillis() - started}ms，原文 ${body.length} 字符"
                    }
                },
                onFailure = { e ->
                    "下载失败：${e.javaClass.simpleName} ${e.message?.take(70) ?: ""}"
                }
            )
            runOnUiThread { done(report) }
        }.start()
    }

    private fun prefs() = getSharedPreferences(Cfg.GROUP, MODE_PRIVATE)

    // ---------------- 三个页签 ----------------

    @Composable
    private fun WuraoApp() {
        var tab by remember { mutableStateOf(tabState) }
        // 直达改的是 Activity 层的 tabState；remember 只在首次组合读一次，
        // 不跟着同步的话"切页"直达在界面已经打开时就完全无效
        LaunchedEffect(tabState) { tab = tabState }
        var openPkg by remember { mutableStateOf<String?>(initialPkg) }

        val detailPkg = openPkg
        MiuixTheme {
            Scaffold(
                topBar = {
                    SmallTopAppBar(
                        title = when {
                            detailPkg != null -> "应用详情"
                            tab == 0 -> "主页"
                            tab == 1 -> "应用"
                            else -> "设置"
                        },
                    )
                },
                bottomBar = {
                    val items = listOf(
                        "主页" to Icons.Default.Assessment,
                        "应用" to Icons.Default.Apps,
                        "设置" to Icons.Default.Settings,
                    )
                    if (floatingBar) {
                        // 旧版默认：悬浮胶囊（圆角、悬空、与屏幕底边有间距）
                        FloatingNavigationBar {
                            items.forEachIndexed { i, (title, vec) ->
                                FloatingNavigationBarItem(
                                    selected = tab == i && detailPkg == null,
                                    onClick = { tab = i; tabState = i; openPkg = null },
                                    icon = vec, label = title,
                                )
                            }
                        }
                    } else {
                        // 关掉悬浮 → 贴底整条
                        NavigationBar {
                            items.forEachIndexed { i, (title, vec) ->
                                NavigationBarItem(
                                    selected = tab == i && detailPkg == null,
                                    onClick = { tab = i; tabState = i; openPkg = null },
                                    icon = vec, label = title,
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        subPage == "appearance" -> AppearanceScreen(
                            floatingBar = floatingBar,
                            onToggleBar = {
                                floatingBar = it
                                prefs().edit().putBoolean("floating_bar", it).apply()
                            },
                        ) { subPage = null }
                        subPage == "about" -> AboutScreen(
                            ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?") ?: "?",
                        ) { subPage = null }
                        detailPkg != null -> AppDetailScreen(detailPkg, { openPkg = null }) { openPkg = null }
                        tab == 0 -> BoardScreen()
                        tab == 1 -> AppsScreen { openPkg = it }
                        else -> SettingsScreen()
                    }
                }
            }
        }
    }

    // ---------------- 看板 ----------------

    /**
     * 看板 = **排错主入口**：这里列出的每一行都是"某条规则真的动过手"，
     * 而你要的正是"功能不正常时，知道该停哪一条" —— 所以每行直接给停用按钮。
     */
    @Composable
    private fun BoardScreen() {
        var rows by remember { mutableStateOf(HitStore.all()) }
        var stopped by remember {
            mutableStateOf(Cfg.decodeList(prefs().getString(Cfg.KEY_STOPPED, null)).toSet())
        }

        fun toggleStop(ruleId: String) {
            val next = if (ruleId in stopped) stopped - ruleId else stopped + ruleId
            stopped = next
            // 停用改规则本身（不是另存一份名单）—— 规则库只有一处，不会两边对不上。
            // 停用不等于删除：规则还在库里，随时能恢复。
            RuleStore.setEnabled(ruleId, ruleId !in stopped)
            rows = HitStore.all()
            pushRulesAsync()
        }

        // 状态卡：一打开就知道现在靠什么在干活（旧版 4.3 的结构）
        val allRules by remember { mutableStateOf(RuleStore.all()) }
        val subs = remember(allRules) { allRules.count { it.origin !is Origin.User } }
        // 命中记录的时间字段不叫 time，不瞎猜；这里就显示总条数
        val recent = rows.size

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------- 状态区（照抄旧版：左边方形大卡 + 右边两个小卡）----------
            val frameOk = CfgPush.bound
            val services = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val a11yOn = services.contains(packageName)
            val enabled = allRules.count { it.enabled }
            val todayCount = rows.size
            val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?") ?: "?"

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(active = frameOk, version = ver, modifier = Modifier.weight(1f).aspectRatio(1f))
                Column(
                    Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(title = "已启用规则", value = "$enabled", modifier = Modifier.weight(1f)) { tabState = 1 }
                    var showHits by remember { mutableStateOf(false) }
                    StatCard(title = "今日拦截", value = "$todayCount", modifier = Modifier.weight(1f)) { showHits = true }
                    if (showHits) {
                        AlertDialog(
                            onDismissRequest = { showHits = false },
                            title = { Text("今日拦截") },
                            text = {
                                if (rows.isEmpty()) {
                                    Text("今天还没拦过任何东西")
                                } else {
                                    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                                        rows.forEach { r ->
                                            Text("· ${r.describe()}（${r.count} 次，${timeAgo(r.lastAtMs)}）", style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                }
                            },
                            confirmButton = { Button(onClick = { showHits = false }) { Text("知道了") } },
                        )
                    }
                }
            }

            if (!frameOk || !a11yOn) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = WARN_BG, contentColor = WARN_FG),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (!frameOk) "LSPosed 未接入" else "无障碍没开",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = WARN_FG,
                        )
                        Text(
                            if (!frameOk) "请确认模块已在 LSPosed 中启用并勾选作用域，然后重新打开应用"
                            else "抓屏和自动化用不了。点这里去开：设置 › 无障碍 › 已下载的服务 › 无扰",
                            fontSize = 14.sp, color = WARN_FG,
                            modifier = if (frameOk) Modifier.clickable {
                                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } else Modifier,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow("系统版本", "Android ${android.os.Build.VERSION.RELEASE}")
                    InfoRow("应用版本", ver)
                    InfoRow("Hook 框架", if (frameOk) "已连接" else "未连接")
                    InfoRow("规则库", "${allRules.size} 条 · 订阅导入 $subs 条")
                    InfoRow("设备型号", android.os.Build.MODEL)
                }
            }

            SmallTitle("常用")
            // 无障碍服务（旧版「常用」里的一项）：开关状态 + 没开时点去开
            Row(
                Modifier.fillMaxWidth().clickable {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AccessibilityNew, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("无障碍服务", fontWeight = FontWeight.Bold)
                    Text(
                        if (a11yOn) "已开启（框架在跑，它只在抓屏时用得上）" else "未开启，抓屏和自动化用不了 —— 点这里去开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            var paused by remember { mutableStateOf(prefs().getString(Cfg.KEY_PAUSED, null) == "true") }
            Button(
                onClick = {
                    paused = !paused
                    prefs().edit().putString(Cfg.KEY_PAUSED, if (paused) "true" else "false").apply()
                    pushRulesAsync()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Block, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (paused) "已全部暂停（点这里恢复）" else "全部暂停")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { tabState = 1 }) { Icon(Icons.Default.Screenshot, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("抓规则") }
                Button(onClick = { tabState = 2 }) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("同步") }
            }
            var linkCheck by remember { mutableStateOf("") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "命中记录 ${rows.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    linkCheck = "检测中…"
                    CfgPush.bind(this@MainActivity) {
                        linkCheck = "已连上 LSPosed（框架正常）"
                        pushRulesAsync()
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!CfgPush.bound) linkCheck = "连不上 —— 去 LSPosed 里确认「无扰」已启用并勾选作用域"
                    }, 3000)
                }) { Icon(Icons.Default.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("检测链接") }
            }
            if (linkCheck.isNotEmpty()) {
                Text(
                    linkCheck,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (linkCheck.startsWith("已连上")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("命中记录", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { rows = HitStore.all() }) { Text("刷新") }
            }
            Text(
                "同一条规则反复命中只占一行（只涨次数），所以开一天 App 也不会刷屏。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (rows.isEmpty()) {
                EmptyHint(
                    Icons.Default.Assessment,
                    "还没有记录",
                    "目标 App 里真的动手之后，记录会从它的进程送回来。",
                )
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.TouchApp,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.pkg, style = MaterialTheme.typography.bodySmall)
                                    Text(r.describe())
                                    Text(
                                        r.target,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        timeAgo(r.lastAtMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (r.ruleId in stopped) {
                                        Text("已停用", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Button(onClick = { toggleStop(r.ruleId) }) {
                                    Text(if (r.ruleId in stopped) "恢复" else "停用")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 应用列表 ----------------

    @Composable
    private fun AppsScreen(onOpen: (String) -> Unit) {
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var note by remember { mutableStateOf("加载中…") }

        LaunchedEffect(Unit) {
            apps = runCatching {
                val pm = packageManager
                // 只列有启动入口的应用（第三方为主），避免几千条系统组件
                val main = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
                val resolved = pm.queryIntentActivities(main, 0)
                resolved.map {
                    val name = it.loadLabel(pm).toString()
                    val pkg = it.activityInfo.packageName
                    pkg to name
                }.distinctBy { it.first }.sortedBy { it.second }
            }.getOrDefault(emptyList())
            note = if (apps.isEmpty()) {
                // HyperOS 会把列表裁剪成只有自己，除非拿到 MIUI 那颗运行时权限
                "拿到了 ${apps.size} 个应用。若只有 1 个，是缺 MIUI 的 GET_INSTALLED_APPS 运行时权限。" +
                    "\n也可以直接在下面输入包名进入详情页。"
            } else {
                "共 ${apps.size} 个应用"
            }
        }

        Column(Modifier.padding(16.dp)) {
            Text("应用", fontWeight = FontWeight.Bold)
            Text(note, style = MaterialTheme.typography.bodySmall)
            // 『包名 + 打开』删了：搜索框已能搜，点开列表项就能进详情
            LazyColumn {
                // 按命中数排序，每行告诉你这个应用拦了多少（旧版 4.3 的结构）
                // 注意：这里是 LazyListScope 的 lambda，不能用 remember（不是 @Composable 上下文）；
                // 好在这只是普通计算，命中记录就那么几条，每次重算也不贵
                val hitCount = HitStore.all().groupBy { it.pkg }.mapValues { e -> e.value.sumOf { h -> h.count } }
                val appsSorted = apps
                    .filter { appQuery.isBlank() || it.first.contains(appQuery, true) || it.second.contains(appQuery, true) }
                    .filter { !appOnlyHit || (hitCount[it.first] ?: 0) > 0 }
                    .sortedByDescending { hitCount[it.first] ?: 0 }
                item {
                    androidx.compose.material3.OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        label = { Text("搜应用（名字或包名）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { appOnlyHit = !appOnlyHit },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Switch(checked = appOnlyHit, onCheckedChange = { appOnlyHit = it })
                        Spacer(Modifier.width(8.dp))
                        Text("只看拦过的", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${appsSorted.size} 个应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(appsSorted) { (pkg, label) ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(pkg) },
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            AppIcon(pkg)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                val n = hitCount[pkg] ?: 0
                                Text(
                                    if (n > 0) "$pkg · 拦了 $n 次" else pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ManualPkgEntry(onOpen: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("包名") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { if (text.isNotBlank()) onOpen(text.trim()) }) { Text("打开") }
        }
    }

    // ---------------- 应用详情：风险级开关 + 自动分析 ----------------

    @Composable
    private fun AppDetailScreen(pkg: String, onBack: () -> Unit, onClose: () -> Unit) {
        var hitVersion by remember { mutableStateOf(0) }
        val hits = remember(hitVersion, pkg) { HitStore.all().filter { it.pkg == pkg } }
        var kinds by remember { mutableStateOf(Cfg.decodeKinds(prefs().getString(Cfg.riskKey(pkg), null))) }
        var candidates by remember { mutableStateOf<List<AdComponentFinder.Candidate>>(emptyList()) }
        var picked by remember { mutableStateOf(Cfg.decodeList(prefs().getString(Cfg.componentsKey(pkg), null)).toSet()) }
        var scanNote by remember { mutableStateOf("") }
        var picks by remember { mutableStateOf<List<SnapshotService.Pick>>(emptyList()) }

        /** 把抓到的控件变成规则并保存（含降级链）。 */
        fun generateFrom(pick: SnapshotService.Pick, act: Act) {
            val rules = RuleGen.rules(pkg, pick.node, pick.container, act = act)
            val old = Cfg.decodeRules(prefs().getString(Cfg.KEY_USER_RULES, null))
            prefs().edit().putString(Cfg.KEY_USER_RULES, Cfg.encodeRules(old + rules)).apply()
            CfgPush.flush(this)
            scanNote = "已生成 ${rules.size} 条（含降级链），第一行：" + rules.first().target
        }

        fun saveKinds(next: Set<RiskGate.Kind>) {
            kinds = next
            prefs().edit().putString(Cfg.riskKey(pkg), Cfg.encodeKinds(next)).apply()
        }

        fun savePicked(next: Set<String>) {
            picked = next
            prefs().edit().putString(Cfg.componentsKey(pkg), Cfg.encodeList(next)).apply()
        }

        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 点进一个应用，先看它命中的规则 + 开关（旧版 4.3 的结构）
        item { SmallTitle("命中的规则（${hits.size}）") }
        if (hits.isNotEmpty()) {
            item {
                val total = hits.sumOf { it.count }
                val lastAt = hits.maxOfOrNull { it.lastAtMs } ?: 0L
                Text(
                    "这个应用拦过 $total 次，最后一次 ${timeAgo(lastAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hits.isEmpty()) {
            item {
                EmptyHint(
                    Icons.Default.TouchApp,
                    "还没命中过",
                    "这个应用里还没动过手。用一阵子，拦过的东西会列在这里，可逐条停用。",
                )
            }
        } else {
            items(hits, key = { it.ruleId }) { h ->
                val rule = RuleStore.all().firstOrNull { it.id == h.ruleId }
                val off = rule?.enabled == false
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            actIcon(Act.fromKey(h.act) ?: Act.HIDE),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.note, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${h.count} 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                h.target,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = {
                            // 停用改规则本身（规则库只有一处，不会两边对不上）；停用不等于删除
                            RuleStore.setEnabled(h.ruleId, off)
                            pushRulesAsync()
                            hitVersion++
                        }) { Text(if (off) "恢复" else "停用") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        item {
                Text(pkg, fontWeight = FontWeight.Bold)
                Text(
                    "风险级能力默认全关。开了之后这个 App 若出现异常，先点「全部退回」。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBack) { Text("返回") }
                    Button(onClick = { saveKinds(emptySet()); savePicked(emptySet()) }) { Text("全部退回") }
                }
            }

item {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "风险级能力",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
            items(RiskGate.Kind.entries.toList()) { kind ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.label)
                        Text(riskHint(kind), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = kinds.contains(kind),
                        onCheckedChange = { on ->
                            saveKinds(if (on) kinds + kind else kinds - kind)
                        },
                    )
                }
            }

            item {
                SmallTitle("还有广告？")
            Text(
                "点下面的按钮，然后切回这个应用 —— 控件会被橙框框出来，点哪个就治哪个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("抓屏", fontWeight = FontWeight.Bold)
                if (!SnapshotService.active) {
                    Text(
                        "抓屏服务没连上。Xposed 读不到别的 App 的界面，这层必须靠无障碍。" +
                            "若设置里已打开过，请关掉再打开一次 —— 更新 APK 会让服务失效，重新开关才会重新绑定。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("去开启") }
                } else {
                    Button(onClick = {
                        SnapshotService.wantPkg = pkg
                        SnapshotService.wantCapture = true
                        scanNote = "已就绪：切到 $pkg，控件会被橙框框出来，点一下就选中并生成规则（点空白处取消）"
                    }) { Icon(Icons.Default.Screenshot, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("抓一屏") }
                }
                if (scanNote.isNotEmpty()) Text(scanNote, style = MaterialTheme.typography.bodySmall)
            }

            items(picks.take(60)) { pick ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            val title = pick.node.text ?: pick.node.desc ?: pick.node.vid
                                ?: pick.node.className.substringAfterLast('.')
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                pick.node.className.substringAfterLast('.') +
                                    (pick.node.vid?.let { " · id=$it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            pick.container?.vid?.let { Text("容器 id=$it", style = MaterialTheme.typography.bodySmall) }
                        }
                        Column {
                            Button(onClick = { generateFrom(pick, Act.HIDE) }) { Text("藏") }
                            Button(onClick = { generateFrom(pick, Act.CLICK) }) { Text("点") }
                        }
                    }
                }
            }

            item {
                Text("自动分析", fontWeight = FontWeight.Bold)
                Button(onClick = {
                    val comps = runCatching {
                        val info = packageManager.getPackageInfo(
                            pkg,
                            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                                PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
                        )
                        buildList {
                            info.activities?.forEach { add("activity" to it.name) }
                            info.services?.forEach { add("service" to it.name) }
                            info.providers?.forEach { add("provider" to it.name) }
                            info.receivers?.forEach { add("receiver" to it.name) }
                        }
                    }.getOrDefault(emptyList())
                    candidates = AdComponentFinder.find(comps)
                    scanNote = "扫到 ${comps.size} 个组件，候选 ${candidates.size} 条（仅供参考，需要你勾选）"
                }) { Text("扫一下这个 App") }
                if (scanNote.isNotEmpty()) Text(scanNote, style = MaterialTheme.typography.bodySmall)
            }

            items(candidates) { c ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = picked.contains(c.name),
                        onCheckedChange = { on ->
                            savePicked(if (on) picked + c.name else picked - c.name)
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(c.name.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                        Text(c.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            (if (c.level == AdComponentFinder.Level.INFRA) "⚠ 基础设施：${c.reason}" else c.reason),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { Button(onClick = onClose) { Text("收起") } }
        }
    }

    private fun riskHint(kind: RiskGate.Kind): String = when (kind) {
        RiskGate.Kind.COMPONENT -> "广告页还没打开就拦掉，不闪不崩"
        RiskGate.Kind.NET -> "广告域名解析到本地，请求不出门"
        RiskGate.Kind.SDK -> "让广告 SDK 起不来（最抗改版，但可能连累 App）"
        RiskGate.Kind.SENSOR -> "摇一摇跳不了。注意：计步、摇一摇换歌、重力操控也靠它"
    }

    // ---------------- 设置 ----------------

    @Composable
    private fun SettingsScreen() {
        var rules by remember { mutableStateOf(RuleStore.all()) }
        // 规则库在后台加载，加载完这里要跟着刷新，否则界面永远显示空
        LaunchedEffect(rulesLoaded) { rules = RuleStore.all() }

        fun save() {
            rules = RuleStore.all()
            pushRulesAsync()
        }

        var pkg by remember { mutableStateOf("") }
        var sel by remember { mutableStateOf("") }
        var act by remember { mutableStateOf(Act.HIDE) }
        var tip by remember { mutableStateOf("") }
        var subText by remember { mutableStateOf("") }
        var subReport by remember { mutableStateOf("") }
        var backupText by remember { mutableStateOf("") }
        var backupTip by remember { mutableStateOf("") }


        fun add() {
            if (pkg.isBlank() || sel.isBlank()) {
                tip = "包名和选择器都要填"
                return
            }
            val r = Rule(
                domain = com.wurao.core.model.Domain.NODE,
                target = sel.trim(),
                act = act,
                scope = if (pkg.trim() == "*") Scope.Global else Scope.App(pkg.trim()),
                origin = Origin.User,
                groupKey = Cfg.GROUP_USER,
                ruleKey = sel.trim().hashCode().toString(),
                note = sel.trim(),
            )
            val issues = r.validate()
            if (issues.isNotEmpty()) {
                tip = "这条规则不合法：" + issues.joinToString("；")
                return
            }
            RuleStore.upsert(r)
            save()
            sel = ""
            tip = "已加（共 ${rules.size} 条）"
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallTitle("规则从哪来")
            Text(
                "广告域名表来自 AdClose（开源的广告 / 追踪域名清单），用来拦广告的网络请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var subExpanded by remember { mutableStateOf(false) }
            SettingRow(
                title = "导入订阅 / 域名表",
                summary = "点开粘贴订阅内容或域名表，也可以填网址",
                icon = Icons.Default.Download,
                expanded = subExpanded,
                onClick = { subExpanded = !subExpanded },
            ) {
            Text(
                "粘贴订阅内容或域名表，或填网址（联网下载）。反复导入同一份不会翻倍，" +
                    "更新订阅也不会动你自己抓的规则。",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.OutlinedTextField(
                value = subText, onValueChange = { subText = it },
                label = { Text("订阅内容或网址") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val t = subText.trim()
                    if (t.isBlank()) {
                        subReport = "先粘贴内容或填网址"
                        return@Button
                    }
                    if (t.startsWith("http")) {
                        subReport = "下载中…"
                        importSourceUrl(t) { subReport = it }
                    } else {
                        subReport = importSource(t)
                    }
                }) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入") }
                Button(onClick = {
                    prefs().edit().remove(Cfg.KEY_SUB_RULES).apply()
                    CfgPush.flush(this@MainActivity)
                    subReport = "已清空订阅规则（你自己抓的不受影响）"
                }) { Text("清空订阅") }
            }
            if (subReport.isNotEmpty()) Text(subReport, style = MaterialTheme.typography.bodySmall)
            }

            var confirmDel by remember { mutableStateOf<Rule?>(null) }
            DeleteConfirm(confirmDel, { confirmDel = null }) {
                confirmDel?.let { RuleStore.remove(it.id); save() }
            }
            var mineExpanded by remember { mutableStateOf(false) }
            SettingRow(
                title = "我自己的规则",
                summary = "手写一条，或者从应用页抓屏挑控件（抓来的更省事）",
                icon = Icons.Default.Add,
                expanded = mineExpanded,
                onClick = { mineExpanded = !mineExpanded },
            ) { }
            if (mineExpanded) {
                Text("我的规则", fontWeight = FontWeight.Bold)
            Text(
                "自己加的规则优先级最高 —— 你是亲眼看到那个东西才加的，比订阅来的更可信。\n" +
                    "例：藏掉美团底栏的视频入口 → 包名 com.sankuai.meituan，" +
                    "选择器 [vid=\"bottom_tab_block\"] << [desc=\"视频\"]",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.OutlinedTextField(
                value = pkg, onValueChange = { pkg = it },
                label = { Text("包名（* 表示所有应用）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            androidx.compose.material3.OutlinedTextField(
                value = sel, onValueChange = { sel = it },
                label = { Text("选择器") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Act.entries.filter { it.allows(com.wurao.core.model.Domain.NODE) }.forEach { a ->
                    Button(onClick = { act = a }) { Text(actCn(a)) }
                }
            }
            Button(onClick = { add() }) { Text("加一条") }
            if (tip.isNotEmpty()) Text(tip, style = MaterialTheme.typography.bodySmall)

            // 规则按来源分组显示。**不能逐条铺开**：库里两万条（大半是广告域名），
            // 一次性组合两万个 Card 会把界面压死（设置页一打开就卡死）。
            // 域名表和订阅各占一行，只有你自己抓的那几条才逐条列。
            val mine = remember(rules) { rules.filter { it.origin is Origin.User } }
            val groups = remember(rules) {
                rules.filter { it.origin !is Origin.User }
                    .groupBy { r ->
                        when (val o = r.origin) {
                            is Origin.Subscription -> "订阅#" + o.subsId + "：" + r.groupKey
                            is Origin.Seed -> "种子 ${o.name}：" + r.groupKey
                            Origin.Builtin -> "内置：" + r.groupKey
                            Origin.User -> "自抓：" + r.groupKey
                        }
                    }
            }

            if (mine.isNotEmpty()) {
                Text("我自己抓的（${mine.size} 条）", fontWeight = FontWeight.Bold)
                mine.forEach { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(
                                actIcon(r.act),
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.target, style = MaterialTheme.typography.bodySmall)
                                val p = when (val sc = r.scope) {
                                    is Scope.App -> sc.pkg
                                    is Scope.Component -> sc.pkg
                                    Scope.Global -> "所有应用"
                                }
                                Text(
                                    p,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Tag(actCn(r.act))
                                    Tag(domainCn(r.domain.key))
                                }
                            }
                            IconButton(onClick = { confirmDel = r }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除这条规则")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (groups.isNotEmpty()) {
                Text("订阅与导入（${groups.values.sumOf { it.size }} 条，合并显示）", fontWeight = FontWeight.Bold)
                groups.forEach { (name, list) ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${list.size} 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 整份删掉：订阅不想要了，一次清干净
                            IconButton(onClick = {
                                list.forEach { RuleStore.remove(it.id) }
                                save()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删掉这一整份")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (rules.isEmpty()) {
                EmptyHint(Icons.Default.Add, "还没有规则", "上面填包名 + 选择器 + 动作加一条，或者从应用页抓屏挑控件。")
            }



            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                                }

            SmallTitle("备份与恢复")
            var backupExpanded by remember { mutableStateOf(false) }
            SettingRow(
                title = "备份 / 恢复",
                summary = "导出成一段文本存着，换手机粘回来",
                icon = Icons.Default.Save,
                expanded = backupExpanded,
                onClick = { backupExpanded = !backupExpanded },
            ) { }
            if (backupExpanded) {
                    Text(
                        "导出成一段文本，复制到任何地方存着；换手机或重装后粘回来即可。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = backupText, onValueChange = { backupText = it },
                        label = { Text("备份内容") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            backupText = exportText()
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("wurao", backupText)
                            )
                            backupTip = "已导出并复制到剪贴板（${backupText.length} 字符）"
                        }) { Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导出") }
                        Button(onClick = {
                            backupTip = restoreText(backupText.trim())
                        }) { Icon(Icons.Default.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("恢复") }
                    }
                    if (backupTip.isNotEmpty()) {
                        Text(backupTip, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
        SmallTitle("外观")
            Row(
                Modifier.fillMaxWidth().clickable {
                    floatingBar = !floatingBar
                    prefs().edit().putBoolean("floating_bar", floatingBar).apply()
                },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("悬浮底栏")
                    Text(
                        "关掉后变成贴底的整条底栏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = floatingBar, onCheckedChange = {
                    floatingBar = it
                    prefs().edit().putBoolean("floating_bar", it).apply()
                })
            }

            SettingRow(
                title = "关于",
                summary = "版本、开发者、项目链接",
                icon = Icons.Default.Info,
                expanded = false,
                onClick = { subPage = "about" },
            ) { }

                }
            }
        }
    }
}

/**
 * 空状态：空列表什么都不显示会让人以为坏了，这里给个大图标 + 一句说明。
 */
@Composable
private fun EmptyHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 应用图标。取不到就退回一个通用图标 —— 列表中一排灰方块很难看，
 * 有图标一眼就能认出是哪个 App。
 */
@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            // 必须固定小尺寸：应用图标 intrinsic 常常是 512x512，
            // 按原尺寸建 Bitmap 一个就 1MB，几百个应用直接把内存吃满
            // （实测堆到 240MB/256MB、界面疯狂 GC）。
            val px = 64
            val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(android.graphics.Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bmp,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        )
    } else {
        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(36.dp))
    }
}

/** 动作的中文说法（模型里只有英文 key，界面上得说人话）。 */
private fun actCn(act: Act): String = when (act.key) {
    "click" -> "点掉"
    "long_click" -> "长按"
    "hide" -> "藏起来"
    "remove" -> "摘掉"
    "block" -> "拦住"
    "goto" -> "跳走"
    "back" -> "返回"
    else -> act.key
}

/** 域的中文说法。 */
private fun domainCn(key: String): String = when (key) {
    "node" -> "控件"
    "host" -> "域名"
    "component" -> "组件"
    "sdk" -> "SDK"
    "sensor" -> "传感器"
    "file" -> "文件"
    else -> key
}

/** 动作对应的图标：一眼看出这条规则是"点掉"还是"藏起来"。 */
private fun actIcon(act: Act): androidx.compose.ui.graphics.vector.ImageVector = when (act.key) {
    "click", "long_click" -> Icons.Default.TouchApp
    "hide" -> Icons.Default.VisibilityOff
    "block" -> Icons.Default.Block
    "remove" -> Icons.Default.Delete
    "back" -> Icons.Default.Close
    else -> Icons.Default.Shield
}

/** 小标签：把"动作/域"从正文拆出来，正文只留选择器。 */
@Composable
private fun Tag(text: String) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 删除确认：规则删了就没了，不能一点就没。 */
@Composable
private fun DeleteConfirm(rule: Rule?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    if (rule == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删掉这条规则？") },
        text = { Text(rule.target) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("删掉") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("不删") } },
    )
}

/**
 * 设置项的一行：标题 + 一句说明，右边箭头，**点开才展开内容**。
 * 之前是一个个输入框直接摊在页面上，很长很乱；收起来之后一屏能看完有哪些项。
 */
@Composable
private fun SettingRow(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    content()
                }
            }
        }
    }
}

/** 多久之前。命中记录只看次数的话，分不清是刚才拦的还是上周拦的。 */
private fun timeAgo(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3_600_000} 小时前"
        d < 7 * 86_400_000L -> "${d / 86_400_000} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}

/** 信息卡的一行：标签 + 值（旧版主页下半部分那种列）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 照抄旧版的主页卡片（配色值、结构、字号都一样） ----------

private val ACTIVE_COLOR = androidx.compose.ui.graphics.Color(0xFF36D167)
private val ACTIVE_BG = androidx.compose.ui.graphics.Color(0xFFDFFAE4)
private val INACTIVE_COLOR = androidx.compose.ui.graphics.Color(0xFFFF5A52)
private val INACTIVE_BG = androidx.compose.ui.graphics.Color(0xFFFFE5E3)
private val WARN_BG = androidx.compose.ui.graphics.Color(0xFFFFF3D6)
private val WARN_FG = androidx.compose.ui.graphics.Color(0xFF704D00)

/** 状态大卡：方形，右下角一个对勾/感叹号水印（照抄旧版）。 */
@Composable
private fun StatusCard(active: Boolean, version: String, modifier: Modifier = Modifier) {
    val color = if (active) ACTIVE_COLOR else INACTIVE_COLOR
    val background = if (active) ACTIVE_BG else INACTIVE_BG
    Card(modifier = modifier, colors = CardDefaults.defaultColors(color = background)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().offset(12.dp, 16.dp),
                contentAlignment = androidx.compose.ui.Alignment.BottomEnd,
            ) {
                OutlineMark(ok = active, tint = color.copy(alpha = 0.78f), size = 110.dp)
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = if (active) "已激活" else "未激活",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color(0xFF101010),
                )
                Text(
                    text = "软件版本：$version",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (active) androidx.compose.ui.graphics.Color(0xFF101010) else color,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 小统计卡：标题小字 + 数字大字（照抄旧版）。 */
@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 描边对勾/感叹号水印（照抄旧版：激活画对勾，未激活画感叹号 —— 未激活不是错误，是「差一步」）。 */
@Composable
private fun OutlineMark(ok: Boolean, tint: androidx.compose.ui.graphics.Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = this.size.minDimension * 0.062f
        val r = this.size.minDimension / 2 - stroke / 2
        drawCircle(color = tint, radius = r, style = Stroke(width = stroke))
        val path = Path().apply {
            if (ok) {
                moveTo(w * 0.295f, h * 0.515f); lineTo(w * 0.445f, h * 0.665f); lineTo(w * 0.715f, h * 0.355f)
            } else {
                moveTo(w * 0.5f, h * 0.30f); lineTo(w * 0.5f, h * 0.56f)
                moveTo(w * 0.5f, h * 0.68f); lineTo(w * 0.5f, h * 0.70f)
            }
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** 外观二级页（照抄旧版：主题 + 底栏）。 */
@Composable
private fun AppearanceScreen(
    floatingBar: Boolean,
    onToggleBar: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                Text("外观", fontWeight = FontWeight.Bold)
            }
        }
        item { SmallTitle("主题") }
        item {
            Text(
                "跟随系统（深色/浅色由系统深色模式决定）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SmallTitle("底栏") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { onToggleBar(!floatingBar) }.padding(14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("悬浮底栏")
                        Text(
                            "关掉后变成贴底的整条底栏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = floatingBar, onCheckedChange = { onToggleBar(it) })
                }
            }
        }
    }
}

/** 关于二级页（照抄旧版：版本、开发者、项目链接）。 */
@Composable
private fun AboutScreen(ver: String, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                Text("关于", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("无扰 $ver", fontWeight = FontWeight.Bold)
                    Text(
                        "从 AdHammer（原「大圣净化」，原作者已停更失联）逆向复刻并重写，" +
                            "规则来源改成开放的订阅制（GKD、域名表）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SmallTitle("项目") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("开源仓库", fontWeight = FontWeight.Bold)
                    Text(
                        "github.com/wangershu/wurao",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("只自用，不分发。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
