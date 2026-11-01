package com.wurao.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        restoreRules()
        // 绑上框架服务后会把本地配置推进 hook 进程；之后每次改动也会推
        CfgPush.bind(this)

        // 调试直达：adb shell am start ... --es add_pkg <包名> --es add_sel_b64 <base64选择器> [--es add_act hide]
        // 省得每次验证都要在界面上点半天（上一版也留了这类入口）。
        if (handleDirect(intent)) {
            finish()
            return
        }

        setContent { WuraoApp() }
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
    private var initialPkg: String? = null

    /** 调试直达要落到哪个页签（--es tab 2）；adb shell 没有 INJECT_EVENTS，
        没法用 input tap 点页签，只能走这条路 */
    private var initialTab: Int = 0

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
        intent?.getStringExtra("export")?.let {
            val text = exportText()
            android.util.Log.i("Wurao", "export: ${text.length} 字符 :: " + text.take(160))
            finish()
            return true
        }
        intent?.getStringExtra("import_sub_b64")?.let {
            val text = runCatching {
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                val report = importSource(text)
                android.util.Log.i("Wurao", "import: $report")
                android.widget.Toast.makeText(this, report, 1).show()
            }
            finish()
            return true
        }
        intent?.getStringExtra("tab")?.toIntOrNull()?.let {
            initialTab = it
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
            val old = Cfg.decodeRules(prefs().getString(Cfg.KEY_USER_RULES, null))
            if (r.validate().isEmpty()) {
                // 用 commit 不用 apply：apply 是异步的，紧接着 finish() 的话
                // 写入任务还没跑完进程就可能没了 —— 表现为"加了规则但没生效"
                val ok = prefs().edit().putString(Cfg.KEY_USER_RULES, Cfg.encodeRules(old + r)).commit()
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
        val looksLikeSub = text.contains("globalGroups") || text.contains("apps")
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
        val looksLikeSub = text.contains("globalGroups") || text.contains("apps")
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
            val h = HostList.parse(text)
            h.hosts.forEach { domain ->
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
            head = "域名表：认出 ${h.hosts.size} 个域名" +
                (if (h.ignoredLines > 0) "，看不懂的行 ${h.ignoredLines} 行" else "")
        }
        pushRules()
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
        pushRules()
        android.util.Log.i("Wurao", "规则迁移：${legacy.size} 条 -> 统一键")
    }

    /** 规则有任何改动都要调一次：写统一键并推送到 hook 进程。 */
    private fun pushRules(): Int {
        val all = RuleStore.all()
        prefs().edit().putString(Cfg.KEY_RULES, Cfg.encodeRules(all)).apply()
        CfgPush.flush(this)
        return all.size
    }

    private fun prefs() = getSharedPreferences(Cfg.GROUP, MODE_PRIVATE)

    // ---------------- 三个页签 ----------------

    @Composable
    private fun WuraoApp() {
        var tab by remember { mutableStateOf(initialTab) }
        var openPkg by remember { mutableStateOf<String?>(initialPkg) }

        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        listOf("看板", "应用", "设置").forEachIndexed { i, title ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i; openPkg = null },
                                label = { Text(title) },
                                icon = {},
                            )
                        }
                    }
                },
            ) { padding ->
                val detail = openPkg
                Column(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        detail != null -> AppDetailScreen(detail, { openPkg = null }) { openPkg = null }
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
            pushRules()
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("命中记录", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { rows = HitStore.all() }) { Text("刷新") }
            }
            Text(
                "同一条规则反复命中只占一行（只涨次数），所以开一天 App 也不会刷屏。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (rows.isEmpty()) {
                Text("还没有记录。目标 App 里真的动手之后，记录会从它的进程送回来。")
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.pkg, style = MaterialTheme.typography.bodySmall)
                                    Text(r.describe())
                                    Text(
                                        r.target,
                                        style = MaterialTheme.typography.bodySmall,
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
            ManualPkgEntry(onOpen)
            LazyColumn {
                items(apps) { (pkg, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(pkg) }
                            .padding(vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label)
                            Text(pkg, style = MaterialTheme.typography.bodySmall)
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

            item { Text("风险级能力", fontWeight = FontWeight.Bold) }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            SnapshotService.wantPkg = pkg
                            SnapshotService.wantCapture = true
                            scanNote = "已就绪：请切到 $pkg，再切回来点「看看抓到没」"
                        }) { Text("抓一屏") }
                        Button(onClick = {
                            picks = SnapshotService.lastSnapshot ?: emptyList()
                            scanNote = "抓到 ${picks.size} 个控件（${SnapshotService.lastPkg ?: "?"}）"
                        }) { Text("看看抓到没") }
                    }
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

        fun save() {
            rules = RuleStore.all()
            pushRules()
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
            Text("导入订阅 / 域名表", fontWeight = FontWeight.Bold)
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
                        // 联网不能在 UI 线程，丢到后台；回来再解析
                        Thread {
                            val body = runCatching { java.net.URL(t).readText() }.getOrNull()
                            runOnUiThread {
                                if (body == null) subReport = "下载失败（网址或网络问题）"
                                else subReport = importSource(body)
                            }
                        }.start()
                    } else {
                        subReport = importSource(t)
                    }
                }) { Text("导入") }
                Button(onClick = {
                    prefs().edit().remove(Cfg.KEY_SUB_RULES).apply()
                    CfgPush.flush(this@MainActivity)
                    subReport = "已清空订阅规则（你自己抓的不受影响）"
                }) { Text("清空订阅") }
            }
            if (subReport.isNotEmpty()) Text(subReport, style = MaterialTheme.typography.bodySmall)

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
                    Button(onClick = { act = a }) { Text(a.key) }
                }
            }
            Button(onClick = { add() }) { Text("加一条") }
            if (tip.isNotEmpty()) Text(tip, style = MaterialTheme.typography.bodySmall)

            rules.forEachIndexed { i, r ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.act.key + " · " + r.target, style = MaterialTheme.typography.bodySmall)
                            val p = when (val s = r.scope) {
                                is Scope.App -> s.pkg
                                is Scope.Component -> s.pkg
                                Scope.Global -> "所有应用"
                            }
                            Text(p, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            RuleStore.remove(r.id)
                            save()
                        }) { Text("删") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("备份 / 恢复", fontWeight = FontWeight.Bold)
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
                        }) { Text("导出") }
                        Button(onClick = {
                            backupTip = restoreText(backupText.trim())
                        }) { Text("恢复") }
                    }
                    if (backupTip.isNotEmpty()) {
                        Text(backupTip, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("自检", fontWeight = FontWeight.Bold)
                    Text("框架：${if (CfgPush.bound) "已连接" else "未连接"}")
                    Text("抓屏服务：${if (SnapshotService.active) "已开启" else "未开启"}")
                    Text("我的规则：${rules.size} 条")
                    Text("命中记录：${HitStore.all().size} 条")
                    val paused = prefs().getString(Cfg.KEY_PAUSED, null) == "true"
                    Text("一键全停：${if (paused) "已停（什么都不做）" else "运行中"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            prefs().edit().putString(Cfg.KEY_PAUSED, "true").apply()
                            CfgPush.flush(this@MainActivity)
                        }) { Text("全部停") }
                        Button(onClick = {
                            prefs().edit().putString(Cfg.KEY_PAUSED, "false").apply()
                            CfgPush.flush(this@MainActivity)
                        }) { Text("恢复") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于", fontWeight = FontWeight.Bold)
                    Text("只自用，不分发。风险级能力默认全关，出问题点「全部退回」。")
                }
            }
        }
    }
}
