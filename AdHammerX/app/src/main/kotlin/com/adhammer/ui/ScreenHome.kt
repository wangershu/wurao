package com.adhammer.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.float.FloatController
import com.adhammer.rules.RuleStore
import com.adhammer.rules.Subscriber
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val GREEN = Color(0xFF34C759)
private val RED = Color(0xFFFF3B30)
private val GREY = Color(0xFF8E8E93)

/**
 * 主页：只做一件事 —— 让你一眼知道「模块现在有没有在工作」。
 *
 * 结构对齐 HyperIsland 的 Overview 页：状态卡 → 子系统 → 数据概览 → 常用入口。
 */
@Composable
fun HomeScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var floatOn by remember { mutableStateOf(FloatController.enabled) }

    LaunchedEffect(Unit) {
        FloatController.syncFrom(ctx)
        floatOn = FloatController.enabled
        UiState.refreshLogs()
    }

    val ruleTotal = UiState.appIndex.sumOf { it.second }
    val subUrl = RuleStore.getMetaUrl(UiState.local) ?: Subscriber.DEFAULT_SUB_URL

    Kit.Page(
        title = "大圣净化",
        subtitle = "AdHammer",
        actionIcon = MiuixIcons.Refresh,
        actionDesc = "同步订阅规则",
        onAction = { scope.launch { UiState.sync(ctx, subUrl) } }
    ) {
        // ---------- 运行状态 ----------
        item { Kit.Section("运行状态") }

        item {
            Kit.HeroCard(
                ok = UiState.active,
                title = if (UiState.active) "框架已接入" else "未检测到框架",
                subtitle = if (UiState.active) {
                    "${UiState.framework} · 规则已下发"
                } else {
                    "在 LSPosed 里启用模块并勾选作用域，再重启目标应用"
                },
                extra = if (UiState.active) "正常" else "待处理"
            )
        }

        item {
            Kit.RowGroup {
                CheckRow("框架服务", UiState.active, UiState.framework)
                CheckRow(
                    "规则库",
                    UiState.appIndex.isNotEmpty(),
                    if (UiState.appIndex.isEmpty()) "还没有规则，先同步一次订阅" else "${UiState.appIndex.size} 个应用"
                )
                CheckRow(
                    "无障碍服务",
                    FloatController.accessibilityOn(ctx),
                    if (FloatController.accessibilityOn(ctx)) "自动跳过可用" else "自动跳过需要开启"
                )
                CheckRow(
                    "悬浮窗权限",
                    FloatController.canOverlay(ctx),
                    if (FloatController.canOverlay(ctx)) "抓快照可用" else "抓快照需要授权"
                )
            }
        }

        // ---------- 三个子系统 ----------
        item { Kit.Section("净化能力") }

        item {
            Kit.RowGroup {
                SubsystemRow(
                    icon = MiuixIcons.Blocklist,
                    name = "页面净化",
                    summary = "Activity / URL / View / 组件 / 文件替换 · $ruleTotal 条规则",
                    on = !UiState.pause && UiState.wuKongOn
                )
                SubsystemRow(
                    icon = MiuixIcons.Play,
                    name = "自动跳过",
                    summary = "无障碍自动点击 · ${UiState.taskCount} 个任务",
                    on = !UiState.pause && UiState.baJieOn
                )
                SubsystemRow(
                    icon = MiuixIcons.Link,
                    name = "域名拦截",
                    summary = "广告域名解析到 127.0.0.1 · ${UiState.hostCount} 个域名",
                    on = !UiState.pause && UiState.shaSengOn
                )
                Kit.ArrowRow(
                    title = "配置净化能力",
                    summary = "三个子系统的开关与细节",
                    onClick = {
                        Navigator.switch(Navigator.Tab.SETTINGS)
                        Navigator.go(Dest.SettingsPurify)
                    }
                )
            }
        }

        // ---------- 数据概览 ----------
        item { Kit.Section("拦截概览") }

        item {
            Kit.Stats(
                listOf(
                    "${UiState.statToday}" to "今日",
                    "${UiState.statWeek}" to "近 7 天",
                    "${UiState.statTotal}" to "累计",
                    "${UiState.appIndex.size}" to "净化应用"
                )
            )
        }

        item {
            Kit.ArrowItem(
                title = "统计详情",
                summary = "趋势、占比与应用排行",
                icon = AppIcons.Stats,
                onClick = { Navigator.switch(Navigator.Tab.STATS) }
            )
        }

        // ---------- 常用 ----------
        item { Kit.Section("常用") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "悬浮窗",
                    summary = "实时显示当前页面，抓取布局快照",
                    icon = Icons.Default.TouchApp,
                    checked = floatOn,
                    onChecked = {
                        floatOn = it
                        FloatController.toggle(ctx, it)
                    }
                )
                Kit.ArrowRow(
                    title = "无障碍服务",
                    summary = "自动跳过依赖它，去系统设置里开启「大圣净化」",
                    icon = Icons.Default.TouchApp,
                    onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                )
                Kit.ArrowRow(
                    title = if (UiState.syncing) "正在同步…" else "同步订阅规则",
                    summary = "最近同步：${UiState.lastSyncTime.ifBlank { "从未同步" }}",
                    icon = MiuixIcons.Link,
                    onClick = { scope.launch { UiState.sync(ctx, subUrl) } }
                )
            }
        }

        if (UiState.lastMessage.isNotBlank()) {
            item { Kit.Note(UiState.lastMessage) }
        }

        // ---------- 最近拦截 ----------
        if (UiState.logs.isNotEmpty()) {
            item { Kit.Section("最近拦截") }
            item {
                Kit.Group {
                    UiState.logs.take(3).forEach { e ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = "${e.what.ifBlank { "—" }} · ${e.action}",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Text(
                                text = e.pkg,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
            item {
                Kit.ArrowItem(
                    title = "查看全部拦截记录",
                    summary = "共 ${UiState.logs.size} 条",
                    icon = MiuixIcons.Search,
                    onClick = { Navigator.go(Dest.Logs) }
                )
            }
        }
    }
}

/** 一行「检查项 + 状态点」 */
@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = if (ok) GREEN else RED)
        }
        Text(
            text = label,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 10.dp)
        )
        Text(
            text = detail,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        )
    }
}

/** 一行子系统状态 */
@Composable
private fun SubsystemRow(
    icon: ImageVector,
    name: String,
    summary: String,
    on: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (on) MiuixTheme.colorScheme.primary else GREY,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = name, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = summary,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Text(
            text = if (on) "已开启" else "已关闭",
            fontSize = 12.sp,
            color = if (on) MiuixTheme.colorScheme.primary else GREY
        )
    }
}

/**
 * 应用管理：按 App 级别控制净化，点箭头进详情看这个 App 有哪些规则（可编辑）。
 */
@Composable
fun AppsScreen(ctx: Context) {
    var query by remember { mutableStateOf("") }

    LaunchedEffect(UiState.appIndex) {
        UiState.ensureLabels(ctx, UiState.appIndex.map { it.first })
    }

    val all = UiState.appIndex
    val list = if (query.isBlank()) all else all.filter { (pkg, _) ->
        pkg.contains(query, true) || UiState.labelOf(pkg).contains(query, true)
    }

    Kit.Page(title = "应用", subtitle = "${all.size} 个应用") {
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索应用",
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { Kit.Section("全局") }

        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "域名拦截",
                    summary = "${UiState.hostCount} 个广告域名被解析到 127.0.0.1",
                    icon = MiuixIcons.Link,
                    onClick = { Navigator.go(Dest.Hosts) }
                )
                Kit.ArrowRow(
                    title = "APK 分析器",
                    summary = "不联网：扫组件 / SDK / 资源名 / 域名，产出候选规则",
                    icon = MiuixIcons.Search,
                    onClick = { Navigator.go(Dest.Analyzer) }
                )
                Kit.ArrowRow(
                    title = "规则工厂",
                    summary = "悬浮窗抓快照 → 本地启发式或 AI → 规则",
                    icon = MiuixIcons.Blocklist,
                    onClick = { Navigator.go(Dest.Factory) }
                )
            }
        }

        item { Kit.Section("应用", "${all.size} 个") }

        if (list.isEmpty()) {
            item {
                Kit.Empty(
                    text = if (all.isEmpty()) "还没有规则：去主页同步一次订阅，或用 APK 分析器造规则"
                    else "没有匹配「$query」的应用",
                    actionText = if (all.isEmpty()) "打开分析器" else null,
                    onAction = if (all.isEmpty()) ({ Navigator.go(Dest.Analyzer) }) else null
                )
            }
        }

        items(list, key = { it.first }) { (pkg, count) ->
            val tasks = UiState.tasksOf(pkg).size
            Kit.ArrowItem(
                title = UiState.labelOf(pkg),
                summary = buildString {
                    append(pkg)
                    append(" · ")
                    append("$count 条规则")
                    if (tasks > 0) append(" · $tasks 个任务")
                },
                onClick = { Navigator.go(Dest.AppDetail(pkg)) },
                end = {
                    Switch(
                        checked = !UiState.appDisabled(pkg),
                        onCheckedChange = { UiState.setAppDisabled(pkg, !it) }
                    )
                }
            )
        }
    }
}

/** 应用详情页里规则条目的摘要文案 */
fun ruleSummary(actionType: Int, ad: String?, remark: String, delay: Long): String {
    val head = com.adhammer.rules.model.Actions.describe(actionType)
    val target = when {
        ad.isNullOrBlank() -> "（未填主键）"
        ad.endsWith(".Url") -> "URL：${ad.removeSuffix(".Url")}"
        ad.endsWith(".Dialog") -> "弹框：${ad.removeSuffix(".Dialog")}"
        ad.endsWith(".Hook") -> "Hook：${ad.removeSuffix(".Hook")}"
        ad == "*" -> "所有页面"
        else -> ad.substringAfterLast('.')
    }
    return buildString {
        append(head)
        append(" · ")
        append(target)
        if (delay > 0) append(" · 延时 ${delay}ms")
        if (remark.isNotBlank()) append("\n$remark")
    }
}

fun typeNameOf(ruleType: Int): String = when (ruleType) {
    com.adhammer.rules.model.RuleTypes.WU_KONG_RULE -> "通用"
    com.adhammer.rules.model.RuleTypes.WU_KONG_URL -> "URL"
    com.adhammer.rules.model.RuleTypes.WU_KONG_VIEW -> "View"
    com.adhammer.rules.model.RuleTypes.WU_KONG_COMPONENT -> "组件"
    com.adhammer.rules.model.RuleTypes.WU_KONG_REPLACE -> "文件"
    com.adhammer.rules.model.RuleTypes.BA_JIE_TASK -> "任务"
    else -> "其他"
}
