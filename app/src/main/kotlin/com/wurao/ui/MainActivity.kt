package com.wurao.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.wurao.assist.FrameOverlay
import com.wurao.assist.SnapshotService
import com.wurao.core.Cfg
import com.wurao.core.analyze.AdComponentFinder
import com.wurao.core.analyze.RuleGen
import com.wurao.core.model.Act
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.store.RuleStore
import kotlinx.coroutines.flow.drop
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 入口与业务胶水。
 *
 * 界面结构对齐大圣净化（AdHammerX）：
 * - 三个根页（主页 / 应用 / 设置）放在 HorizontalPager 里，底栏只在根页显示；
 * - 二级页（应用详情 / 命中记录 / 设置子页）整层覆盖，返回键 / 手势逐级退回；
 * - 导航走 [Navigator] 返回栈，调试直达（am start --es）翻译成 Navigator 调用。
 *
 * 视觉组件全部走 miuix（Kit.kt），信息架构与业务逻辑与旧版一致。
 */
class MainActivity : ComponentActivity() {

    /**
     * 备份/恢复的文件通道（SAF 系统文件选择器，不要存储权限）。
     * 导出内容是 JSON（所有配置的键值对）；恢复是整份覆盖，
     * 所以确认弹窗会先告诉用户里面有多少项。
     */
    private val exportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(Ui.exportText(this).toByteArray()) }
            }.onSuccess {
                toast("已导出备份（JSON）")
            }.onFailure {
                toast("导出失败：${it.message ?: "写不进去"}")
            }
        }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toast("读不到这个文件")
                return@registerForActivityResult
            }
            val n = runCatching { org.json.JSONObject(text).length() }.getOrDefault(-1)
            android.app.AlertDialog.Builder(this)
                .setTitle("恢复备份？")
                .setMessage(if (n >= 0) "里面包含 $n 项配置，恢复是整份覆盖。" else "这段不是有效的备份（解析失败）")
                .setPositiveButton("恢复") { _, _ ->
                    if (n >= 0) toast(Ui.restoreText(this, text))
                }
                .setNegativeButton("算了", null)
                .show()
        }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, 1).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 备份页的两个按钮接到 SAF 启动器上
        BackupBridge.onExportClick = {
            val day = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            exportFile.launch("wurao-backup-$day.json")
        }
        BackupBridge.onImportClick = { importFile.launch(arrayOf("application/json", "text/*")) }
        // 把落盘的命中记录读回内存（这些记录是目标进程通过 HitProvider 写进来的）
        HitProvider.restore(this)
        // 同步加载，不再放后台 —— 后台会和推送抢：加载没完就推送，
        // 会把"半成品"写回去覆盖完整配置，实测静默丢了 6700 条规则。
        // 2.8 万条实测不卡（ANR 记录新增 0），为它冒竞态的险不值。
        Ui.restoreRules(this)
        Ui.rulesLoaded = true
        // 绑上框架服务后会把本地配置推进 hook 进程；之后每次改动也会推
        CfgPush.bind(this)
        UiPrefs.load(this)

        // 调试直达：adb shell am start ... --es tab 1 / --es dest hits / --es open_pkg <包名> ...
        // 省得每次验证都要在界面上点半天（设备没有 INJECT_EVENTS，点不了屏幕）。
        if (handleDirect(intent)) {
            finish()
            return
        }

        setContent { AppTheme { WuraoApp() } }
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
    private fun handleDirect(intent: android.content.Intent?): Boolean {
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
        // 往某个 App 的拦截名单里加一个组件：--es add_component <包名> --es component <类名>
        intent?.getStringExtra("add_component")?.let { pkg ->
            val comp = intent?.getStringExtra("component")?.trim()
            if (comp.isNullOrBlank()) {
                finish()
                return true
            }
            val key = Cfg.componentsKey(pkg)
            val old = Cfg.decodeList(Ui.prefs(this).getString(key, null)).toMutableSet()
            old += comp
            Ui.prefs(this).edit().putString(key, Cfg.encodeList(old)).apply()
            CfgPush.flush(this)
            android.util.Log.i("Wurao", "组件拦截[$pkg] 共 ${old.size} 个，新加: $comp")
            finish()
            return true
        }
        // 风险级开关直达：--es risk <包名> --es kinds COMPONENT,NET,SDK,SENSOR
        // 验完用 --es risk_clear <包名> 关回去（这些能力默认必须全关）
        intent?.getStringExtra("risk")?.let { pkg ->
            val kinds = Cfg.decodeKinds(intent?.getStringExtra("kinds"))
            Ui.prefs(this).edit().putString(Cfg.riskKey(pkg), Cfg.encodeKinds(kinds)).apply()
            CfgPush.flush(this)
            android.util.Log.i("Wurao", "风险级[$pkg] = ${Cfg.encodeKinds(kinds)}")
            finish()
            return true
        }
        intent?.getStringExtra("risk_clear")?.let { pkg ->
            Ui.prefs(this).edit().remove(Cfg.riskKey(pkg)).apply()
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
            Ui.importSourceUrl(ctx = this, url = url) { report ->
                android.util.Log.i("Wurao", "import_url: $report")
                android.widget.Toast.makeText(this, report, 1).show()
                finish()
            }
            return false
        }
        intent?.getStringExtra("export")?.let {
            val text = Ui.exportText(this)
            android.util.Log.i("Wurao", "export: ${text.length} 字符 :: " + text.take(160))
            finish()
            return true
        }
        // 删掉某条规则（按选择器精确匹配）—— 验证产物不能留在库里，
        // 否则会真的藏掉人家首页上的按钮
        intent?.getStringExtra("del_rule")?.let { sel ->
            val hit = RuleStore.all().filter { it.target == sel }
            hit.forEach { RuleStore.remove(it.id) }
            Ui.pushRulesAsync(this)
            android.util.Log.i("Wurao", "del_rule: 删除 ${hit.size} 条（target=$sel）")
            finish()
            return true
        }
        // 对某个 App 跑自动分析（扫它的组件挖广告候选）—— 本地扫 APK，
        // 不需要目标进程被 hook，所以作用域没加也能跑
        intent?.getStringExtra("analyze")?.let { pkg ->
            Ui.runInBackground(this, onDone = { finish() }) {
                val pm = packageManager
                val pi = runCatching {
                    pm.getPackageInfo(
                        pkg,
                        PackageManager.GET_ACTIVITIES or
                            PackageManager.GET_SERVICES or
                            PackageManager.GET_RECEIVERS or
                            PackageManager.GET_PROVIDERS
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
            val ok = FrameOverlay.show(svc, picks) { pick, act ->
                val rules = RuleGen.rules(pick.pkg ?: "", pick.node, pick.container, act = act)
                rules.forEach { RuleStore.upsert(it) }
                Ui.pushRulesAsync(this)
                android.widget.Toast.makeText(
                    svc,
                    "已保存 ${rules.size} 条：${rules.firstOrNull()?.target}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                FrameOverlay.hide(svc)
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
            Ui.pushRulesAsync(this)
            android.util.Log.i(
                "Wurao",
                "gen_rule: 由「${pick.node.text ?: pick.node.desc ?: pick.node.vid}」生成 ${rules.size} 条，" +
                    "第一条 = ${rules.firstOrNull()?.target}"
            )
            finish()
            return true
        }
        // 导入一律**放后台**：一万多条域名在主线程解析+入库+推送会把界面卡死（实测 ANR）
        intent?.getStringExtra("import_file")?.let { path ->
            Ui.runInBackground(this, onDone = { finish() }) {
                // 不以 / 开头就当文件名，读 App 自己的目录（filesDir）——
                // 硬编码 /data/data/... 在不同机型/多用户下可能不对，而且外部路径往往没权限读
                val file = if (path.startsWith("/")) java.io.File(path)
                else java.io.File(filesDir, path)
                android.util.Log.i("Wurao", "读文件: ${file.absolutePath} 存在=${file.exists()} 可读=${file.canRead()}")
                val body = runCatching { file.readText() }.getOrNull()
                if (body.isNullOrBlank()) "读不到文件：${file.absolutePath}" else Ui.importSource(this, body)
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
            Ui.runInBackground(this, onDone = { finish() }) { Ui.importSource(this, raw) }
            return false
        }
        // 页签直达：--es tab 0/1/2
        intent?.getStringExtra("tab")?.toIntOrNull()?.let { i ->
            Navigator.Tab.entries.getOrNull(i)?.let { Navigator.switch(it) }
            return false
        }
        // 页面直达：--es dest hits / sources / backup / appearance / about
        intent?.getStringExtra("dest")?.let { name ->
            when (name.trim().lowercase()) {
                "hits" -> Navigator.jump(Dest.Hits, Navigator.Tab.HOME)
                "sources" -> Navigator.jump(Dest.Sources, Navigator.Tab.SETTINGS)
                "backup" -> Navigator.jump(Dest.Backup, Navigator.Tab.SETTINGS)
                "appearance" -> Navigator.jump(Dest.Appearance, Navigator.Tab.SETTINGS)
                "about" -> Navigator.jump(Dest.About, Navigator.Tab.SETTINGS)
            }
            return false
        }
        // 应用详情直达：--es open_pkg <包名>
        intent?.getStringExtra("open_pkg")?.takeIf { it.isNotBlank() }?.let {
            Navigator.jump(Dest.AppDetail(it), Navigator.Tab.APPS)
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
                Ui.pushRulesAsync(this)
                android.util.Log.i("Wurao", "addRule ok=true pkg=$addPkg act=${act.key}")
                android.widget.Toast.makeText(this, "已加规则：${act.key} $addPkg", 1).show()
            } else {
                android.util.Log.w("Wurao", "addRule 非法：${r.validate().joinToString()}")
                android.widget.Toast.makeText(this, "规则不合法：${r.validate().joinToString()}", 1).show()
            }
            return true
        }
        return false
    }

    // ---------------- 根结构：pager + 底栏 + 二级页覆盖层 ----------------

    @Composable
    private fun WuraoApp() {
        val pagerState = rememberPagerState(pageCount = { Navigator.Tab.entries.size })

        // Navigator.tab → pager（直达切页、点底栏都走这里）
        LaunchedEffect(Navigator.tab) {
            if (pagerState.currentPage != Navigator.tab.ordinal) {
                pagerState.animateScrollToPage(Navigator.tab.ordinal)
            }
        }
        // pager → Navigator：只在根页回写（drop(1) 防首帧抖动把二级页弹回根页）
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.drop(1).collect { p ->
                Navigator.Tab.entries.getOrNull(p)?.let { t ->
                    if (Navigator.atRoot && t != Navigator.tab) Navigator.switch(t)
                }
            }
        }

        BackHandler(enabled = Navigator.canBack) { Navigator.back() }

        Scaffold(
            bottomBar = { if (Navigator.atRoot) BottomBar() },
        ) { padding ->
            // 根 padding 只取「底栏自身的高度」下发给列表；系统栏内边距交给各页自己的 Scaffold，
            // 否则状态栏会被算两遍、整个顶栏下移一个状态栏高度。
            val navBarInset = with(LocalDensity.current) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            var barInset = (padding.calculateBottomPadding() - navBarInset).coerceAtLeast(0.dp)
            // 悬浮底栏要额外补一截：FloatingNavigationBar 自带「悬浮外边距」
            // （胶囊和屏幕底边之间那段），Scaffold 下发的 bottomPadding 只算测量高度，
            // 不含那段外边距 —— 不补的话列表末行会滑到底栏底下去（真机截图实测）。
            if (UiPrefs.floatingBar) barInset += 24.dp
            CompositionLocalProvider(
                Kit.LocalBottomBarInset provides barInset
            ) {
                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (Navigator.Tab.entries[page]) {
                            Navigator.Tab.HOME -> HomeScreen()
                            Navigator.Tab.APPS -> AppsScreen()
                            Navigator.Tab.SETTINGS -> SettingsScreen()
                        }
                    }
                    // 二级页：整层覆盖，底色用 surface（background 是卡片白，用错会"白一块"）
                    if (!Navigator.atRoot) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MiuixTheme.colorScheme.surface)
                        ) {
                            when (val d = Navigator.current) {
                                is Dest.AppDetail -> AppDetailScreen(d.pkg)
                                Dest.Hits -> HitsScreen()
                                Dest.Sources -> SourcesScreen()
                                Dest.Backup -> BackupScreen()
                                Dest.Appearance -> AppearanceScreen()
                                Dest.About -> AboutScreen()
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 底栏：悬浮胶囊（默认）或贴底整条。
     *
     * 选中高亮必须自绘：miuix 默认选中/未选中只差 alpha，几乎看不出来。
     * 悬浮栏选中项外面包一层 primary 14% 的圆角胶囊。
     */
    @Composable
    private fun BottomBar() {
        val items = listOf(
            Triple("主页", MiuixIcons.Home, Navigator.Tab.HOME),
            Triple("应用", MiuixIcons.GridView, Navigator.Tab.APPS),
            Triple("设置", MiuixIcons.Settings, Navigator.Tab.SETTINGS),
        )
        val colors = NavigationBarDefaults.navigationBarItemColors(
            unselectedContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            selectedContentColor = MiuixTheme.colorScheme.primary,
        )
        if (UiPrefs.floatingBar) {
            FloatingNavigationBar {
                items.forEach { (title, vec, t) ->
                    val sel = Navigator.tab == t
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                    ) {
                        FloatingNavigationBarItem(
                            selected = sel,
                            onClick = { Navigator.switch(t) },
                            icon = vec,
                            label = title,
                            colors = colors,
                        )
                    }
                }
            }
        } else {
            NavigationBar {
                items.forEach { (title, vec, t) ->
                    NavigationBarItem(
                        selected = Navigator.tab == t,
                        onClick = { Navigator.switch(t) },
                        icon = vec,
                        label = title,
                        colors = colors,
                    )
                }
            }
        }
    }
}
