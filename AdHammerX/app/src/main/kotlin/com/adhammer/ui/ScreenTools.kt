package com.adhammer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.analyzer.ApkAnalyzer
import com.adhammer.float.FloatController
import com.adhammer.rules.RuleStore
import com.adhammer.rules.Subscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 工具页：造规则的三个入口 + 订阅。
 */
@Composable
fun ToolsScreen(ctx: Context, onPickApk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var floatOn by remember { mutableStateOf(FloatController.enabled) }
    var subUrl by remember { mutableStateOf(RuleStore.getMetaUrl(UiState.local) ?: Subscriber.DEFAULT_SUB_URL) }

    LaunchedEffect(Unit) {
        FloatController.syncFrom(ctx)
        floatOn = FloatController.enabled
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Kit.Bar(title = "工具")

    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = Kit.pad(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Kit.Section("抓规则") }

        item {
            Kit.SwitchItem(
                title = "悬浮窗",
                summary = "显示当前页面，一键抓布局快照",
                icon = Icons.Default.TouchApp,
                checked = floatOn,
                onChecked = {
                    floatOn = it
                    FloatController.toggle(ctx, it)
                }
            )
        }

        item {
            Kit.Item(
                title = "无障碍服务",
                summary = "八戒自动点击 + 快照抓 View 树都需要它",
                icon = Icons.Default.Extension,
                onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            )
        }

        item {
            Kit.Item(
                title = "规则工厂（快照生成）",
                summary = "用当前页面的布局快照造规则，可本地生成也可交给 AI",
                icon = Icons.Default.Build,
                onClick = { Navigator.go(Dest.Factory) }
            )
        }

        item {
            Kit.Item(
                title = "APK 分析器",
                summary = "不联网：扫组件 / 广告 SDK / 资源名 / 域名，产出候选规则",
                icon = Icons.Default.Analytics,
                onClick = { Navigator.go(Dest.Analyzer) }
            )
        }

        item { Kit.Section("验证") }

        item {
            Kit.Item(
                title = "规则自检",
                summary = "拿真实规则跑一遍判定：三个子系统到底能不能干活",
                icon = Icons.Default.CheckCircle,
                onClick = { Navigator.go(Dest.SelfTest) }
            )
        }

        item {
            Kit.Item(
                title = "悟空实验室",
                summary = "填 Activity / id / 域名，看会命中哪些规则",
                icon = Icons.Default.Tune,
                onClick = { Navigator.go(Dest.Lab) }
            )
        }

        item { Kit.Section("规则订阅") }

        item {
            Kit.Group {
                Text(
                    text = "规则与代码完全解耦，换地址即可换整套规则。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                TextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = "订阅地址",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = { scope.launch { UiState.sync(ctx, subUrl) } }) {
                        Text(text = if (UiState.syncing) "同步中…" else "同步")
                    }
                }
                Text(
                    text = "最近同步：${UiState.lastSyncTime.ifBlank { "从未同步" }}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (UiState.lastMessage.isNotBlank()) {
                    Text(
                        text = UiState.lastMessage,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item { Kit.Section("分析目标 APK") }
        item {
            Kit.Item(
                title = "从文件里选一个 APK",
                summary = UiState.pendingApk?.substringAfterLast('/') ?: "分析没安装的 APK 时用这个",
                icon = Icons.Default.PhoneAndroid,
                onClick = onPickApk
            )
        }
    }
    }
}

/**
 * APK 分析器：把「这个 App 塞了哪些广告」摆出来，勾一勾就变成规则。
 *
 * 全程本地计算，不需要大模型，也不需要联网。
 */
@Composable
fun AnalyzerScreen(ctx: Context, onPickApk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ApkAnalyzer.Report?>(null) }
    var picks by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pickInstalled by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = "APK 分析器",
            subtitle = "本地分析，不联网",
            canBack = true,
            onBack = { Navigator.back() },
            actions = {
                TextButton(text = "选 APK", onClick = onPickApk, enabled = !busy)
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Kit.Section("分析目标") }

            item {
                Kit.Group {
                    TextField(
                        value = target,
                        onValueChange = { target = it },
                        label = "已安装应用的包名",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(text = "选择已安装应用", onClick = { pickInstalled = true })
                    }
                    val apk = UiState.pendingApk
                    if (apk != null) {
                        Text(
                            text = "已选文件：$apk",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            TextButton(text = "分析这个文件", onClick = {
                                scope.launch { runAnalyze(ctx, File(apk)) { r, p ->
                                    report = r; picks = p; busy = false
                                } }
                                busy = true
                            }, enabled = !busy)
                            TextButton(
                                text = "清除",
                                onClick = { UiState.updatePendingApk(null) },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.padding(horizontal = Kit.H_PAD)) {
                    Button(
                        onClick = {
                            if (target.isBlank()) {
                                msg = "先填包名或从文件里选一个 APK"
                                return@Button
                            }
                            busy = true
                            msg = "分析中…（大 App 可能要几秒）"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { ApkAnalyzer.installed(ctx, target) }
                                report = r
                                picks = r.candidates.associate { it.id to it.checked }
                                busy = false
                                msg = ""
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = if (busy) "分析中…" else "开始分析")
                    }
                }
            }

            if (msg.isNotBlank()) item { Kit.Note(msg) }

            val r = report
            if (r != null) {
                item {
                    Kit.Section("分析结果", r.pkg)
                }
                item {
                    Kit.Group {
                        Kit.KeyValue("应用", r.label ?: r.pkg)
                        if (!r.version.isNullOrBlank()) Kit.KeyValue("版本", r.version!!)
                        Kit.KeyValue("组件数", "${r.componentCount}")
                        Kit.KeyValue("dex 类数", "${r.dexClassCount}")
                        Kit.KeyValue("命中广告 SDK", if (r.sdks.isEmpty()) "未命中" else r.sdks.joinToString("、"))
                        if (r.launcher != null) Kit.KeyValue("启动页", r.launcher!!.substringAfterLast('.'))
                    }
                }

                if (r.error != null) {
                    item { Kit.Empty(text = r.error!!) }
                } else if (r.candidates.isEmpty()) {
                    item {
                        Kit.Empty(
                            text = "没找到明显的广告痕迹。可以更新特征库（设置页）后再试，或用规则工厂从快照造规则。"
                        )
                    }
                } else {
                    ApkAnalyzer.Cat.values().forEach { cat ->
                        val list = r.candidates.filter { it.cat == cat }
                        if (list.isEmpty()) return@forEach
                        item {
                            Kit.Section(
                                when (cat) {
                                    ApkAnalyzer.Cat.ACTIVITY -> "Activity（建议拦截启动）"
                                    ApkAnalyzer.Cat.RECEIVER -> "广播接收器"
                                    ApkAnalyzer.Cat.SERVICE -> "服务"
                                    ApkAnalyzer.Cat.PROVIDER -> "内容提供者"
                                    ApkAnalyzer.Cat.VIEW -> "疑似广告控件（资源名）"
                                    ApkAnalyzer.Cat.HOST -> "疑似广告域名"
                                },
                                extra = "${list.size} 项"
                            )
                        }
                        items(list) { c ->
                            Kit.Item(
                                title = c.title,
                                summary = c.summary,
                                onClick = { picks = picks + (c.id to !(picks[c.id] ?: false)) },
                                end = {
                                    Switch(
                                        checked = picks[c.id] ?: false,
                                        onCheckedChange = { picks = picks + (c.id to it) }
                                    )
                                }
                            )
                        }
                    }

                    item {
                        Row(modifier = Modifier.padding(horizontal = Kit.H_PAD, vertical = 8.dp)) {
                            Button(
                                onClick = {
                                    val chosen = r.candidates.filter { picks[it.id] == true }
                                    if (chosen.isEmpty()) {
                                        msg = "还没勾选任何候选"
                                        return@Button
                                    }
                                    val (packs, hosts) = ApkAnalyzer.toRules(r.pkg, chosen)
                                    msg = UiState.saveAnalyzed(r.pkg, packs, hosts)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "保存勾选的规则")
                            }
                        }
                    }
                    item {
                        Kit.Note("保存后与订阅规则合并（追加，不覆盖）。去「规则」页找到该应用可以逐条微调。")
                    }
                }

                if (r.notes.isNotEmpty()) {
                    item { Kit.Section("说明") }
                    item {
                        Kit.Group {
                            r.notes.forEach {
                                Text(
                                    text = "· $it",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickInstalled) {
        InstalledPicker(
            onDismiss = { pickInstalled = false },
            onPick = { pkg -> target = pkg; pickInstalled = false }
        )
    }
}

private suspend fun runAnalyze(
    ctx: Context,
    file: File,
    onDone: (ApkAnalyzer.Report, Map<String, Boolean>) -> Unit
) {
    val r = withContext(Dispatchers.IO) { ApkAnalyzer.file(ctx, file) }
    onDone(r, r.candidates.associate { it.id to it.checked })
}

/** 已安装应用选择器 */
@Composable
private fun InstalledPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val list = runCatching {
                pm.getInstalledPackages(PackageManager.GET_ACTIVITIES).mapNotNull { pi ->
                    val name = runCatching {
                        pi.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                    }.getOrNull()
                    val label = if (name.isNullOrBlank() || name == pi.packageName) pi.packageName else name
                    pi.packageName to label
                }.sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
            items = list
        }
    }

    OverlayDialog(
        show = true,
        title = "选择应用",
        onDismissRequest = onDismiss
    ) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val shown = items.filter {
                it.first.contains(query, true) || it.second.contains(query, true)
            }.take(300)
            LazyColumn(modifier = Modifier.height(360.dp).padding(top = 8.dp)) {
                items(shown) { (pkg, label) ->
                    Kit.Item(
                        title = label,
                        summary = pkg,
                        onClick = { onPick(pkg) }
                    )
                }
            }
        }
    }
}
