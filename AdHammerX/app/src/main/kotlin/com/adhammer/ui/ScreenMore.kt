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
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.Task
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
 * 本地启发式不联网；AI 是可选增强（同一份快照，schema 完全一致）。
 */
@Composable
fun FactoryScreen(ctx: Context) {
    val scope = rememberCoroutineScope()

    var pkg by remember { mutableStateOf(RuleGen.pageOf(UiState.pendingSnapshot).second ?: "") }
    var snapshot by remember { mutableStateOf(UiState.pendingSnapshot.ifBlank { Snapshot.last() }) }
    var packs by remember { mutableStateOf<List<RulePack>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var preview by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = "规则工厂",
            subtitle = "快照 → 规则",
            canBack = true,
            onBack = { Navigator.back() }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Kit.Section("目标") }
            item {
                Kit.Group {
                    TextField(
                        value = pkg,
                        onValueChange = { pkg = it },
                        label = "目标包名",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(text = "读取最新快照", onClick = {
                            val s = UiState.pendingSnapshot.ifBlank { Snapshot.last() }
                            if (s.isBlank()) {
                                msg = "还没有快照：开悬浮窗 → 停在广告页 → 点「快照」"
                            } else {
                                snapshot = s
                                val p = RuleGen.pageOf(s)
                                if (pkg.isBlank()) pkg = p.second ?: ""
                                msg = "已载入：${p.first ?: "未知页面"}"
                            }
                        })
                    }
                }
            }

            item { Kit.Section("布局快照", "${snapshot.lineCount()} 行") }
            item {
                Kit.Group {
                    if (snapshot.isBlank()) {
                        Text(
                            text = "还没有快照。开悬浮窗 → 停在广告页 → 点「快照」→ 回到这里生成。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    } else {
                        Text(
                            text = snapshot,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.height(160.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            item { Kit.Section("生成") }
            item {
                Kit.Group {
                    Text(
                        text = "本地生成按「跳过/关闭/广告/skip/close」等文案定位；AI 生成需要填 Key。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(text = "本地生成", onClick = {
                            if (snapshot.isBlank()) {
                                msg = "请先载入快照"
                                return@TextButton
                            }
                            val p = RuleGen.pageOf(snapshot)
                            if (pkg.isBlank()) pkg = p.second ?: ""
                            val r = RuleGen.heuristic(pkg, snapshot)
                            packs = r.packs
                            tasks = r.tasks
                            preview = Jsonx.toJson(r.packs) ?: ""
                            msg = if (r.packs.isEmpty()) "没找到广告特征，试试 AI 生成或手工写"
                            else "生成 ${r.packs.sumOf { it.rules.size }} 条规则 + ${r.tasks.sumOf { it.steps.size }} 步任务"
                        })
                        TextButton(
                            text = if (busy) "AI 生成中…" else "AI 生成",
                            enabled = !busy,
                            onClick = {
                                if (snapshot.isBlank() || pkg.isBlank()) {
                                    msg = "快照和包名都要有"
                                    return@TextButton
                                }
                                if (!AiConfig.isReady(ctx)) {
                                    msg = "还没填 AI 接口信息（设置页）"
                                    return@TextButton
                                }
                                busy = true
                                msg = "AI 生成中…"
                                scope.launch {
                                    val json = AiRuleGen.generate(ctx, pkg, snapshot)
                                    val pack = RuleGen.fromAiJson(json)
                                    busy = false
                                    if (pack == null) {
                                        msg = "AI 没返回可用规则"
                                    } else {
                                        packs = listOf(pack)
                                        tasks = emptyList()
                                        preview = json ?: ""
                                        msg = "AI 生成 ${pack.rules.size} 条规则"
                                    }
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp)
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
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.height(180.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            if (msg.isNotBlank()) item { Kit.Note(msg) }

            item {
                Row(modifier = Modifier.padding(horizontal = Kit.H_PAD, vertical = 8.dp)) {
                    Button(
                        onClick = {
                            msg = UiState.saveGenerated(pkg, packs, tasks)
                            packs = emptyList()
                            tasks = emptyList()
                            preview = ""
                        },
                        enabled = packs.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(text = "保存到规则库") }
                }
            }
        }
    }
}

private fun String.lineCount() = lineSequence().count()

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
