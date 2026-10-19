package com.adhammer.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.BuildConfig
import com.adhammer.float.FloatController
import com.adhammer.rules.RuleStore
import com.adhammer.rules.Subscriber
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

// 状态卡配色，取自参考项目 HyperIsland 的 OverviewPage
private val ACTIVE_COLOR = Color(0xFF36D167)
private val ACTIVE_BG = Color(0xFFDFFAE4)
private val INACTIVE_COLOR = Color(0xFFFF5A52)
private val INACTIVE_BG = Color(0xFFFFE5E3)
private val WARN_BG_LIGHT = Color(0xFFFFF3D6)
private val WARN_FG_LIGHT = Color(0xFF704D00)

/**
 * 主页：一眼看出「模块现在有没有在工作」。
 *
 * 版式照搬 HyperIsland 的 Overview：
 * 顶部 1 大 + 2 小的状态网格（左大卡带对勾水印），下面接告警卡、信息卡、入口卡。
 */
@Composable
fun HomeScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var floatOn by remember { mutableStateOf(FloatController.enabled) }

    LaunchedEffect(Unit) {
        FloatController.syncFrom(ctx)
        floatOn = FloatController.enabled
        UiState.refreshLogs()
        UiState.refreshStats()
    }

    val ruleTotal = UiState.appIndex.sumOf { it.second }
    val subUrl = RuleStore.getMetaUrl(UiState.local) ?: Subscriber.DEFAULT_SUB_URL

    Kit.Page(
        title = "大圣净化",
        actionIcon = MiuixIcons.Refresh,
        actionDesc = "同步订阅规则",
        onAction = { scope.launch { UiState.sync(ctx, subUrl) } },
        bottomExtra = 16.dp
    ) {
        // ---------- 状态网格 ----------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusCard(
                    active = UiState.active,
                    version = BuildConfig.VERSION_NAME,
                    modifier = Modifier.weight(1f).aspectRatio(1f)
                )
                Column(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        title = "已适配应用",
                        value = "${UiState.appIndex.size}",
                        modifier = Modifier.weight(1f),
                        onClick = { Navigator.switch(Navigator.Tab.APPS) }
                    )
                    StatCard(
                        title = "今日拦截",
                        value = "${UiState.statToday}",
                        modifier = Modifier.weight(1f),
                        onClick = { Navigator.switch(Navigator.Tab.STATS) }
                    )
                }
            }
        }

        // ---------- 未激活告警 ----------
        if (!UiState.active) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = WARN_BG_LIGHT,
                        contentColor = WARN_FG_LIGHT
                    ),
                    showIndication = false
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "LSPosed 未接入",
                            color = WARN_FG_LIGHT,
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "请确认模块已在 LSPosed 中启用并勾选作用域，然后重新打开应用",
                            color = WARN_FG_LIGHT.copy(alpha = 0.82f),
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
            }
        }

        // ---------- 信息卡 ----------
        item {
            Kit.Group {
                InfoText("系统版本", "Android ${Build.VERSION.RELEASE}")
                InfoText("应用版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                InfoText("Hook 框架", if (UiState.active) "LSPosed 102 · ${UiState.framework}" else "未连接")
                InfoText("规则库", "${UiState.appIndex.size} 个应用 · $ruleTotal 条规则 · ${UiState.hostCount} 个域名")
                InfoText("设备型号", Build.MODEL, bottom = 0.dp)
            }
        }

        // ---------- 常用 ----------
        item { Kit.Section("常用") }
        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "悬浮窗",
                    summary = "实时显示当前页面，抓取布局快照",
                    icon = MiuixIcons.ScreenCapture,
                    checked = floatOn,
                    onChecked = {
                        floatOn = it
                        FloatController.toggle(ctx, it)
                    }
                )
                Kit.ArrowRow(
                    title = "无障碍服务",
                    summary = if (FloatController.accessibilityOn(ctx)) "已开启，自动跳过可用" else "未开启，自动跳过不可用",
                    icon = MiuixIcons.Unlock,
                    onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                )
                Kit.ArrowRow(
                    title = if (UiState.syncing) "正在同步…" else "同步订阅规则",
                    summary = "最近同步：${UiState.lastSyncTime.ifBlank { "从未同步" }}",
                    icon = MiuixIcons.Link,
                    onClick = { scope.launch { UiState.sync(ctx, subUrl) } }
                )
                Kit.ArrowRow(
                    title = "配置净化能力",
                    summary = "三个子系统的开关与细节",
                    icon = MiuixIcons.Blocklist,
                    onClick = {
                        Navigator.switch(Navigator.Tab.SETTINGS)
                        Navigator.go(Dest.SettingsPurify)
                    }
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

/** 左侧大状态卡：对勾水印 + 状态 + 版本 */
@Composable
private fun StatusCard(active: Boolean, version: String, modifier: Modifier = Modifier) {
    val color = if (active) ACTIVE_COLOR else INACTIVE_COLOR
    val background = if (active) ACTIVE_BG else INACTIVE_BG
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = background),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                // 参考项目用的是「描边圆圈 + 勾」的 drawable，不是 material 的实心 CheckCircle
                OutlineCheck(tint = color.copy(alpha = 0.78f), size = 110.dp)
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = if (active) "已激活" else "未激活",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010)
                )
                Text(
                    text = "软件版本：$version",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) Color(0xFF101010) else color,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 描边对勾圆圈水印：外圈圆 + 折线勾，笔宽按尺寸等比 */
@Composable
private fun OutlineCheck(tint: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.062f
        val r = this.size.minDimension / 2 - stroke / 2
        drawCircle(
            color = tint,
            radius = r,
            style = Stroke(width = stroke)
        )
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.295f, this@Canvas.size.height * 0.515f)
            lineTo(this@Canvas.size.width * 0.445f, this@Canvas.size.height * 0.665f)
            lineTo(this@Canvas.size.width * 0.715f, this@Canvas.size.height * 0.355f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 右侧小数据卡 */
@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 信息卡里的「加粗标题 + 灰色内容」两行 */
@Composable
private fun InfoText(title: String, content: String, bottom: Dp = 24.dp) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface
    )
    Text(
        text = content,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottom)
    )
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
                    append("$count 条规则")
                    if (tasks > 0) append(" · $tasks 个任务")
                },
                start = { AppIcon(pkg) },
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
