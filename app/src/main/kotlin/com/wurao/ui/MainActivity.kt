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
import com.wurao.core.Cfg
import com.wurao.core.RiskGate
import com.wurao.core.analyze.AdComponentFinder

/**
 * 界面。**信息架构沿用上一版**：底部三个页签（看板 / 应用 / 设置）。
 *
 * 视觉组件先用 material3 把骨架跑通；上一版用的是 miuix（MIUI 风格），
 * 骨架稳定后再换回去 —— 换的是组件库，页签与层级不变。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WuraoApp() }
    }

    private fun prefs() = getSharedPreferences(Cfg.GROUP, MODE_PRIVATE)

    // ---------------- 三个页签 ----------------

    @Composable
    private fun WuraoApp() {
        var tab by remember { mutableStateOf(0) }
        var openPkg by remember { mutableStateOf<String?>(null) }

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

    @Composable
    private fun BoardScreen() {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("看板", fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("框架状态：以日志为准")
                    Text(
                        "模块在目标 App 里生效时，目标进程会打出「已装配」日志。\n" +
                            "注意走的不是 Wurao 这个 tag，而是 LSPosedFramework。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("命中记录（待接入）", fontWeight = FontWeight.Bold)
            Text(
                "记录逻辑已经写好并按规则聚合，但从目标进程回传到界面还差一个通道，下一步接。",
                style = MaterialTheme.typography.bodySmall,
            )
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
    }

    // ---------------- 设置 ----------------

    @Composable
    private fun SettingsScreen() {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("设置", fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("规则源", fontWeight = FontWeight.Bold)
                    Text(
                        "GKD 订阅与域名表的解析已经写好（导入即生效、反复导入不翻倍），" +
                            "界面上的导入入口下一步接。",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
