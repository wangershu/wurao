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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.BuildConfig
import com.adhammer.float.FloatController
import com.adhammer.rules.RuleStore
import com.adhammer.rules.Subscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.SelectAll
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
        // 已安装集合决定「已启用规则」的计数口径和应用页的排序
        withContext(Dispatchers.IO) { UiState.refreshInstalled(ctx) }
    }

    val ruleTotal = UiState.appIndex.sumOf { it.second }
    val subUrl = RuleStore.getMetaUrl(UiState.local) ?: Subscriber.DEFAULT_SUB_URL

    Kit.Page(
        title = "大圣净化",
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
                        title = "已启用规则",
                        value = "${UiState.ruleAppCount}",
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

        // ---------- 未激活：一句话告警就够了 ----------
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
        } else {
            // 已激活：换成运行数据 —— 这时候用户关心的是「它拦了多少」
            item { Kit.Section("近 7 天拦截") }
            item {
                Kit.Group {
                    if (UiState.statDays.all { it.second == 0L }) {
                        Text(
                            text = "还没有命中记录。打开目标应用逛一圈，这里就会长出来。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    } else {
                        BarChart(UiState.statDays)
                    }
                }
            }
            item {
                Kit.ArrowItem(
                    title = "查看统计明细",
                    summary = "今日 ${UiState.statToday} · 累计 ${UiState.statTotal}",
                    icon = MiuixIcons.Search,
                    onClick = { Navigator.switch(Navigator.Tab.STATS) }
                )
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
        // clip：对勾水印是按参考项目那样「从右下角溢出去一点」摆的，
        // 不裁就会跑出卡片边界压到旁边的数据卡上
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
            Box(
                // 参考项目是 (27,31)，但它的卡片更宽，照搬会让勾的收笔正好压在裁切边上，
                // 这里往左上收一点，留出安全边
                modifier = Modifier.fillMaxSize().offset(12.dp, 16.dp),
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
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(UiState.appIndex) {
        UiState.ensureLabels(ctx, UiState.appIndex.map { it.first })
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UiState.refreshInstalled(ctx) }
    }
    // 退出多选后把选择清掉，下次进来是干净的
    LaunchedEffect(selectionMode) {
        if (!selectionMode) selected = emptySet()
    }

    val all = UiState.appIndex
    val list = if (query.isBlank()) all else all.filter { (pkg, _) ->
        pkg.contains(query, true) || UiState.labelOf(pkg).contains(query, true)
    }
    // 装了的排前面：没装的 app 规则留着，但既不在首页计数，也沉到列表底部。
    // 包名表还没捞到时（首帧 / 个别 ROM 查不到）不做拆分，否则会把满屏都标成「未安装」。
    val knownInstalled = UiState.installed.isNotEmpty()
    val (installedList, missingList) = if (knownInstalled) {
        list.partition { (pkg, _) -> UiState.isInstalled(pkg) }
    } else {
        list to emptyList()
    }

    val visiblePkgs = list.map { it.first }
    val allSelected = visiblePkgs.isNotEmpty() && selected.containsAll(visiblePkgs)
    val closeAction: (@Composable () -> Unit)? = if (selectionMode) {
        {
            IconButton(onClick = { selectionMode = false }) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = "退出多选")
            }
        }
    } else {
        null
    }

    Kit.Page(
        title = if (selectionMode) "已选择 ${selected.size} 个" else "应用",
        subtitle = if (selectionMode) "" else "${all.size} 个应用",
        navigation = closeAction,
        actions = {
            // 参考项目的做法：右上角一个 SelectAll，非多选时进入多选，多选时全选/取消全选
            IconButton(
                onClick = {
                    if (selectionMode) {
                        selected = if (allSelected) emptySet() else visiblePkgs.toSet()
                    } else {
                        selectionMode = true
                    }
                }
            ) {
                Icon(
                    imageVector = MiuixIcons.SelectAll,
                    contentDescription = if (allSelected) "取消全选" else "全选"
                )
            }
        }
    ) {
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

        if (selectionMode) {
            item {
                Kit.Group {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            text = "启用选中",
                            onClick = { UiState.setAppsDisabled(selected, false) },
                            enabled = selected.isNotEmpty()
                        )
                        TextButton(
                            text = "停用选中",
                            onClick = { UiState.setAppsDisabled(selected, true) },
                            enabled = selected.isNotEmpty()
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (selected.isEmpty()) "点一行勾选" else "已选 ${selected.size}",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
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

        // 只有靠谱地知道「装了什么」时才分两组，否则一组平铺
        if (knownInstalled && installedList.isNotEmpty()) {
            item { Kit.Section("已安装", "${installedList.size} 个") }
        }
        items(installedList, key = { it.first }) { (pkg, count) ->
            AppRow(
                ctx = ctx,
                pkg = pkg,
                count = count,
                installed = true,
                selectionMode = selectionMode,
                checked = pkg in selected,
                onToggle = {
                    selected = if (pkg in selected) selected - pkg else selected + pkg
                }
            )
        }

        if (missingList.isNotEmpty()) {
            item { Kit.Section("未安装", "${missingList.size} 个 · 不计入首页统计") }
            items(missingList, key = { it.first }) { (pkg, count) ->
                AppRow(
                    ctx = ctx,
                    pkg = pkg,
                    count = count,
                    installed = false,
                    selectionMode = selectionMode,
                    checked = pkg in selected,
                    onToggle = {
                        selected = if (pkg in selected) selected - pkg else selected + pkg
                    }
                )
            }
        }
    }
}

/** 应用列表的一行：图标 + 名字 + 规则数 + 右侧总开关 + 箭头进详情；多选时换成复选框 */
@Composable
private fun AppRow(
    ctx: Context,
    pkg: String,
    count: Int,
    installed: Boolean,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {}
) {
    val tasks = UiState.tasksOf(pkg).size
    val summary = buildString {
        append("$count 条规则")
        if (tasks > 0) append(" · $tasks 个任务")
        if (!installed) append(" · 未安装")
    }
    if (selectionMode) {
        Kit.PlainRow(
            title = UiState.labelOf(pkg),
            summary = summary,
            start = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                        onClick = null
                    )
                    Spacer(Modifier.width(14.dp))
                    AppIcon(pkg)
                }
            },
            onClick = onToggle
        )
    } else {
        // 右侧是「开关 + 18dp 的 ChevronForward」，两者之间留 8dp ——
        // 参考项目的应用行就是这个组合；之前用 10x16 的 ArrowRight 贴在开关上，挤在一起不好看
        Kit.PlainRow(
            title = UiState.labelOf(pkg),
            summary = summary,
            start = { AppIcon(pkg) },
            onClick = { Navigator.go(Dest.AppDetail(pkg)) },
            end = {
                Switch(
                    checked = !UiState.appDisabled(pkg),
                    onCheckedChange = { UiState.setAppDisabled(pkg, !it) }
                )
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp)
                )
            }
        )
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
