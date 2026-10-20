package com.adhammer.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.R
import com.adhammer.ai.AiConfig
import com.adhammer.ai.AiRuleGen
import com.adhammer.analyzer.AdFeatures
import com.adhammer.float.Snapshot
import com.adhammer.rules.Cfg
import com.adhammer.rules.Jsonx
import com.adhammer.rules.RuleGen
import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.Step
import com.adhammer.rules.model.Task
import com.adhammer.rules.model.ViewRule
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 规则工厂：布局快照 → 规则。
 *
 * 对应原版「悬浮窗 → 快照 → 查看布局 → 勾选节点 → 生成规则」。
 * 悬浮窗点「快照」后会直接跳到这里，并把节点结构化地带过来，可以逐条勾选；
 * 如果只想让它自己猜，也有「一键猜广告控件」和 AI 生成两条兜底路径。
 */
@Composable
fun FactoryScreen(ctx: Context) {
    val scope = rememberCoroutineScope()

    // 悬浮窗抓完的快照（同进程单例，比走 Intent 传文本更完整）
    val cap = Snapshot.pending
    val pickedPoint = Snapshot.picked

    var pkg by remember(cap) { mutableStateOf(cap?.pkg.orEmpty()) }
    var act by remember(cap) { mutableStateOf(cap?.act.orEmpty()) }
    var chosen by remember(cap) { mutableStateOf<Set<Int>>(emptySet()) }
    var withTask by remember { mutableStateOf(true) }
    var packs by remember { mutableStateOf<List<RulePack>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var preview by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    val nodes = cap?.nodes ?: emptyList()

    // 候选：优先可点击的，没有就退到「有文本的」
    val candidates = remember(nodes) {
        val clickable = nodes.withIndex().filter { (_, n) -> n.clickable }
        (if (clickable.isNotEmpty()) clickable
        else nodes.withIndex().filter { (_, n) -> !n.text.isNullOrBlank() })
            .take(120)
    }

    fun build() {
        if (pkg.isBlank()) {
            msg = "先填目标包名"
            return
        }
        val sel = chosen.sorted().mapNotNull { nodes.getOrNull(it) }
        if (sel.isEmpty()) {
            msg = "先勾选至少一个控件（列表里点一下就是选中）"
            return
        }
        val items = sel.map { n ->
            RuleItem(
                actionType = Actions.VIEW,
                ad = act.takeIf { it.isNotBlank() },
                adKey = Jsonx.toJson(
                    listOf(
                        ViewRule(
                            v = n.cls,
                            s = true,
                            id = n.id,
                            t = n.text,
                            ht = 1,
                            pl = 1,
                            m = "快照生成"
                        )
                    )
                ),
                isEnable = true,
                remark = "快照生成：" + n.label.take(20)
            )
        }
        packs = listOf(
            RulePack(
                isEnable = true,
                name = "悬浮窗生成",
                pkg = pkg,
                ruleType = RuleTypes.WU_KONG_VIEW,
                rules = items
            )
        )
        tasks = if (!withTask) emptyList() else listOf(
            Task(
                act = act.takeIf { it.isNotBlank() },
                isEnable = true,
                name = "悬浮窗生成-点击",
                pkg = pkg,
                ruleType = RuleTypes.BA_JIE_TASK,
                steps = sel.map { n -> Step(at = 8, vId = n.id, t = n.text, wv = n.cls, c = 1, d = 300) }
            )
        )
        preview = Jsonx.toJson(packs).orEmpty()
        msg = "生成 ${items.size} 条 View 规则" +
            if (withTask) " + ${sel.size} 步点击任务" else ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = "规则工厂",
            subtitle = if (cap == null) "还没有快照" else cap.actShort,
            canBack = true,
            onBack = { Navigator.back() }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (cap == null) {
                item { Kit.Note("开悬浮窗 → 停在广告页 → 点「快照」，会自动回到这里。") }
                item {
                    Kit.Empty(
                        text = "还没有快照。规则工厂的原料是目标页面当前的控件树。",
                        actionText = "打开悬浮窗",
                        onAction = { com.adhammer.float.FloatController.toggle(ctx, true) }
                    )
                }
                return@LazyColumn
            }

            // ---------- 目标 ----------
            item { Kit.Section("目标", "${nodes.size} 个节点") }
            item {
                Kit.Group {
                    TextField(
                        value = pkg,
                        onValueChange = { pkg = it },
                        label = "目标包名",
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = act,
                        onValueChange = { act = it },
                        label = "Activity（留空 = 所有页面）",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(modifier = Modifier.padding(top = 10.dp)) {
                        TextButton(text = "一键猜广告控件", onClick = {
                            val snapText = cap.text
                            val r = RuleGen.heuristic(pkg, snapText)
                            if (r.packs.isEmpty()) {
                                msg = "没猜出广告特征，手动勾选更稳"
                            } else {
                                packs = r.packs
                                tasks = r.tasks
                                preview = Jsonx.toJson(r.packs).orEmpty()
                                msg = "猜出 ${r.packs.sumOf { it.rules.size }} 条规则 + ${r.tasks.sumOf { it.steps.size }} 步任务"
                            }
                        })
                        TextButton(
                            text = if (busy) "AI 中…" else "AI 生成",
                            enabled = !busy,
                            onClick = {
                                if (!AiConfig.isReady(ctx)) {
                                    msg = "还没填 AI 接口（设置 → 高级）"
                                    return@TextButton
                                }
                                busy = true
                                msg = "AI 生成中…"
                                scope.launch {
                                    val json = AiRuleGen.generate(ctx, pkg, cap.text)
                                    val pack = RuleGen.fromAiJson(json)
                                    busy = false
                                    if (pack == null) {
                                        msg = "AI 没返回可用规则"
                                    } else {
                                        packs = listOf(pack)
                                        tasks = emptyList()
                                        preview = json.orEmpty()
                                        msg = "AI 生成 ${pack.rules.size} 条规则"
                                    }
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // ---------- 坐标 ----------
            if (pickedPoint != null && pickedPoint.size == 2) {
                item { Kit.Section("取的坐标") }
                item {
                    Kit.ArrowRow(
                        title = "(${pickedPoint[0]}, ${pickedPoint[1]})",
                        summary = "点一下加进八戒步骤（没有稳定 id / 文本时用坐标）",
                        icon = Icons.Filled.TouchApp,
                        onClick = {
                            val step = Step(at = 8, x = pickedPoint[0], y = pickedPoint[1], d = 200)
                            tasks = if (tasks.isEmpty()) {
                                listOf(
                                    Task(
                                        act = act.takeIf { it.isNotBlank() },
                                        isEnable = true,
                                        name = "悬浮窗生成-坐标",
                                        pkg = pkg,
                                        ruleType = RuleTypes.BA_JIE_TASK,
                                        steps = listOf(step)
                                    )
                                )
                            } else {
                                tasks.mapIndexed { i, t ->
                                    if (i == 0) t.copy(steps = t.steps + step) else t
                                }
                            }
                            Snapshot.clearPick()
                            msg = "已加一步坐标点击（八戒任务）"
                        }
                    )
                }
            }

            // ---------- 候选控件 ----------
            item {
                Kit.Section(
                    text = "选择控件",
                    extra = if (chosen.isEmpty()) "点一行选中" else "已选 ${chosen.size} 个"
                )
            }
            if (candidates.isEmpty()) {
                item { Kit.Note("这一页没有可点击控件，换个页面再抓。") }
            } else {
                item {
                    Kit.RowGroup {
                        candidates.take(60).forEach { (i, n) ->
                            val on = i in chosen
                            Kit.PlainRow(
                                title = n.label.take(24),
                                summary = buildString {
                                    append(n.short)
                                    n.id?.let { append(" · #").append(it) }
                                    if (n.bounds.size == 4) {
                                        append(" · ")
                                        append(n.bounds[2] - n.bounds[0]).append("×")
                                        append(n.bounds[3] - n.bounds[1])
                                    }
                                },
                                start = {
                                    androidx.compose.material3.Checkbox(
                                        checked = on,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    chosen = if (on) chosen - i else chosen + i
                                }
                            )
                        }
                    }
                }
                if (candidates.size > 60) {
                    item { Kit.Note("只列了前 60 个可点击控件，剩下的用「一键猜」或 AI。") }
                }
            }

            // ---------- 选项 ----------
            item { Kit.Section("生成") }
            item {
                Kit.RowGroup {
                    Kit.SwitchRow(
                        title = "同时生成八戒点击步骤",
                        summary = "悟空 View 规则没盖住时，靠无障碍点掉",
                        checked = withTask,
                        onChecked = { withTask = it }
                    )
                    Kit.ArrowRow(
                        title = "查看原始快照",
                        summary = if (showRaw) "已展开 ${cap.text.lineSequence().count()} 行" else "缩进树 + 资源 id + 坐标",
                        onClick = { showRaw = !showRaw }
                    )
                }
            }
            if (showRaw) {
                item {
                    Kit.Group {
                        Text(
                            text = cap.text,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            if (preview.isNotBlank()) {
                item { Kit.Section("预览") }
                item {
                    Kit.Group {
                        Text(
                            text = preview,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.height(180.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            if (msg.isNotBlank()) item { Kit.Note(msg) }

            item {
                Row(
                    modifier = Modifier.padding(horizontal = Kit.H_PAD, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { build() },
                        enabled = chosen.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(text = "生成规则") }
                    Button(
                        onClick = {
                            msg = UiState.saveGenerated(pkg, packs, tasks)
                            packs = emptyList()
                            tasks = emptyList()
                            preview = ""
                            chosen = emptySet()
                            Snapshot.clearPending()
                        },
                        enabled = packs.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(text = "保存") }
                }
            }
        }
    }
}

/**
 * 沙僧：域名黑名单编辑。
 */
@Composable
fun HostsScreen(ctx: Context) {
    var query by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    val all = UiState.hosts
    val shown = all.filter { it.contains(query, true) }.take(300)

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = "沙僧域名",
            subtitle = "共 ${all.size} 个，命中即解析到 127.0.0.1",
            canBack = true,
            onBack = { Navigator.back() }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Kit.Section("添加域名") }
            item {
                Kit.Group {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        label = "如 ad.xxx.com（支持粘贴 127.0.0.1 开头）",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = {
                            if (input.isBlank()) return@Button
                            UiState.addHost(input)
                            input = ""
                            Toast.makeText(ctx, "已添加", Toast.LENGTH_SHORT).show()
                        }) { Text(text = "添加") }
                    }
                }
            }

            item { Kit.Section("域名列表", "显示 ${shown.size}/${all.size}") }

            if (shown.isEmpty()) {
                item { Kit.Empty(text = if (all.isEmpty()) "还没有域名，同步订阅或手工添加" else "没有匹配「$query」的域名") }
            }

            item {
                Kit.Group {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索域名",
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            items(shown) { host ->
                Kit.Item(
                    title = host,
                    icon = Icons.Default.Dns,
                    onClick = null,
                    end = {
                        IconButton(onClick = { UiState.removeHost(host) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        }
    }
}

/**
 * 拦截日志：看「刚刚拦掉了什么」，用来验证规则是否真的生效。
 */
@Composable
fun LogsScreen(ctx: Context) {
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    val logs = UiState.logs

    Column(modifier = Modifier.fillMaxSize()) {
        Kit.Bar(
            title = "拦截日志",
            subtitle = "最近 ${logs.size} 条",
            canBack = true,
            onBack = { Navigator.back() },
            actions = {
                TextButton(text = "清空", onClick = { UiState.clearLogs() })
            }
        )

        if (logs.isEmpty()) {
            Kit.Empty(
                text = "还没有拦截记录。\n确认模块已激活、目标 App 在作用域内，然后打开它逛一圈再回来看。",
                actionText = "刷新",
                onAction = { UiState.refreshLogs() }
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs) { e ->
                Kit.Item(
                    title = "${e.what.ifBlank { "—" }} · ${e.action}",
                    summary = "${fmt.format(java.util.Date(e.ts))} · ${e.pkg}",
                    onClick = null
                )
            }
        }
    }
}
