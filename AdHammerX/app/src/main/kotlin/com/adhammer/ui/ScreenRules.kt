package com.adhammer.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.rules.Cfg
import com.adhammer.rules.Jsonx
import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RuleTypes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ==================== 三个子系统的规则分类 ====================

/** 悟空的一类规则：列表标题 + 一句话说明 + 编辑器里的填写提示 */
private data class RuleKind(
    val type: Int,
    val title: String,
    val desc: String,
    val hint: String
)

/**
 * 悟空名下五类规则。
 *
 * 原版把这些挂在「拦截模式 → App」下面逐类编辑，这里沿用同样的分法：
 * 每类一个入口，进去是该类的规则列表，再点进去是编辑器（表单 / JSON 双模式）。
 */
private val WU_KONG_KINDS = listOf(
    RuleKind(
        RuleTypes.WU_KONG_RULE, "通用规则",
        "关闭 / 禁用 / 跳过到首页 / 改写跳转 / 弹框 / 自定义 Hook",
        "ad 填广告页 Activity 全名；以 .Dialog 结尾是弹框规则，以 .Hook 结尾是自定义 Hook。"
    ),
    RuleKind(
        RuleTypes.WU_KONG_URL, "URL 拦截",
        "按域名或完整链接拦掉请求",
        "ad 固定填 xxx.Url，adKey 填域名（如 ad.xxx.com）或完整链接。"
    ),
    RuleKind(
        RuleTypes.WU_KONG_VIEW, "精准 View",
        "按 id / 文本 / 父子层级定位后隐藏或点击",
        "ad 填广告所在 Activity，填 * 表示所有页面；adKey 是 View 定位的 JSON 数组。"
    ),
    RuleKind(
        RuleTypes.WU_KONG_COMPONENT, "组件屏蔽",
        "Activity / Receiver / Service / Provider / Intent 防火墙",
        "ad 填组件全名。at=9 是 Intent 防火墙，在 startActivity 阶段把 Intent 掏空，效果最好。"
    ),
    RuleKind(
        RuleTypes.WU_KONG_REPLACE, "文件替换",
        "替换成空文件 / 空文件夹 / 直接删除",
        "ad 填文件路径；adKey 填替换目标，留空则替换成空文件。"
    )
)

private fun kindOf(ruleType: Int): RuleKind? = WU_KONG_KINDS.firstOrNull { it.type == ruleType }

/** 分组标题右侧的条数角标 */
@Composable
private fun CountBadge(n: Int) {
    Text(
        text = n.toString(),
        fontSize = 13.sp,
        color = if (n > 0) MiuixTheme.colorScheme.onSurfaceVariantSummary
        else MiuixTheme.colorScheme.disabledOnSurface
    )
}

/**
 * 应用详情：按「悟空 / 八戒 / 沙僧」三个子系统分块。
 *
 * 层级对齐原版：拦截模式 → App → 该 App 的规则。
 * 每块一个总开关（这个应用要不要装这个子系统），块内是该类规则的入口。
 */
@Composable
fun AppDetailScreen(ctx: Context, pkg: String) {
    // 规则存在 SharedPreferences 里，本身不是 Compose state；
    // 靠 revision 变化重新读一次，写完立刻能看到结果。
    val rev = UiState.revision
    val packs = remember(pkg, rev) { UiState.packsOf(pkg) }
    val tasks = remember(pkg, rev) { UiState.tasksOf(pkg) }
    val mine = remember(pkg, rev) { UiState.appHostsOf(pkg) }

    var askClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 参考项目里的详情页是「紧凑栏 + 顶部实体头」，标题写在头部块里
        Kit.CompactBar(
            onBack = { Navigator.back() },
            actions = {
                // 单个 App 的元信息：首页 Activity + 跳过提示文案
                IconButton(onClick = { Navigator.go(Dest.HomeAct(pkg)) }) {
                    Icon(imageVector = MiuixIcons.Tune, contentDescription = "应用设置")
                }
                IconButton(onClick = { askClear = true }) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "清空这个应用的规则",
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }
        )

        // 应用级开关：关掉后这个 app 一条 hook 都不装（白名单）
        Kit.Header(
            title = UiState.labelOf(pkg),
            subtitle = pkg,
            start = { AppIcon(pkg, 44.dp) },
            end = {
                Switch(
                    checked = !UiState.appDisabled(pkg),
                    onCheckedChange = { UiState.setAppDisabled(pkg, !it) }
                )
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------------- 悟空 ----------------
            item {
                Kit.Section(
                    text = "悟空 · 页面与控件拦截",
                    extra = "共 ${packs.sumOf { it.rules.size }} 条"
                ) {
                    Switch(
                        checked = !UiState.subOff(pkg, Cfg.SUB_WU_KONG),
                        onCheckedChange = { UiState.setSubOff(pkg, Cfg.SUB_WU_KONG, !it) }
                    )
                }
            }
            item {
                Kit.RowGroup {
                    WU_KONG_KINDS.forEach { kind ->
                        val n = packs.filter { it.ruleType == kind.type }.sumOf { it.rules.size }
                        Kit.ArrowRow(
                            title = kind.title,
                            summary = kind.desc,
                            end = { CountBadge(n) },
                            onClick = { Navigator.go(Dest.RuleList(pkg, kind.type)) }
                        )
                    }
                }
            }

            // ---------------- 八戒 ----------------
            item {
                Kit.Section(
                    text = "八戒 · 自动跳过任务",
                    extra = "共 ${tasks.size} 个"
                ) {
                    Switch(
                        checked = !UiState.subOff(pkg, Cfg.SUB_BA_JIE),
                        onCheckedChange = { UiState.setSubOff(pkg, Cfg.SUB_BA_JIE, !it) }
                    )
                }
            }
            item {
                Kit.RowGroup {
                    Kit.ArrowRow(
                        title = "任务列表",
                        summary = if (tasks.isEmpty()) "还没有任务（订阅同步后会自动带过来）"
                        else "按 Activity 匹配后顺序执行点击 / 滑动",
                        end = { CountBadge(tasks.size) },
                        onClick = { Navigator.go(Dest.TaskList(pkg)) }
                    )
                }
            }

            // ---------------- 沙僧 ----------------
            item {
                Kit.Section(
                    text = "沙僧 · 域名拦截",
                    extra = "应用 ${mine.size} · 全局 ${UiState.hostCount}"
                ) {
                    Switch(
                        checked = !UiState.subOff(pkg, Cfg.SUB_SHA_SENG),
                        onCheckedChange = { UiState.setSubOff(pkg, Cfg.SUB_SHA_SENG, !it) }
                    )
                }
            }
            item {
                Kit.RowGroup {
                    Kit.ArrowRow(
                        title = "此应用的域名",
                        summary = if (mine.isEmpty()) "还没有，可以手写或由 APK 分析生成"
                        else "只对 $pkg 生效",
                        end = { CountBadge(mine.size) },
                        onClick = { Navigator.go(Dest.AppHosts(pkg)) }
                    )
                    Kit.ArrowRow(
                        title = "全局域名黑名单",
                        summary = "订阅同步 · 对所有应用生效",
                        end = { CountBadge(UiState.hostCount) },
                        onClick = { Navigator.go(Dest.Hosts) }
                    )
                }
            }

            item {
                Kit.Note(
                    "每块右侧的开关是「这个应用要不要装这个子系统」，用来排查是哪一块在起作用；" +
                        "顶部的大开关是整应用白名单。规则改动在目标 App 下次启动生效。"
                )
            }
        }
    }

    if (askClear) {
        OverlayDialog(
            show = true,
            title = "清空这个应用的全部规则？",
            summary = "$pkg\n悟空规则、八戒任务、专属域名都会被删掉，订阅同步后会重新拉回来。",
            onDismissRequest = { askClear = false }
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { askClear = false })
                TextButton(text = "清空", onClick = {
                    UiState.clearApp(pkg)
                    askClear = false
                    Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}

/**
 * 悟空某一类规则的列表页。
 *
 * 条目可开关（不动订阅原文，只改启用位）、可进编辑器、长按或点垃圾桶删除。
 */
@Composable
fun RuleListScreen(ctx: Context, pkg: String, ruleType: Int) {
    val rev = UiState.revision
    val rules = remember(pkg, ruleType, rev) { UiState.rulesOf(pkg, ruleType) }
    val kind = kindOf(ruleType)
    var pendingDelete by remember { mutableIntStateOf(-1) }

    Kit.DetailPage(
        title = kind?.title ?: UiState.typeName(ruleType),
        subtitle = pkg,
        onBack = { Navigator.back() },
        actionIcon = MiuixIcons.Add,
        actionDesc = "新增规则",
        onAction = { Navigator.go(Dest.RuleEdit(pkg, ruleType, -1)) }
    ) {
        if (kind != null) item { Kit.Note(kind.hint) }

        if (rules.isEmpty()) {
            item {
                Kit.Empty(
                    text = "这一类还没有规则，右上角 + 可以手写一条",
                    actionText = "新增规则",
                    onAction = { Navigator.go(Dest.RuleEdit(pkg, ruleType, -1)) }
                )
            }
        } else {
            itemsIndexed(rules) { index, rule ->
                Kit.Item(
                    title = shortNameOf(rule.ad),
                    summary = compactSummary(rule),
                    onClick = { Navigator.go(Dest.RuleEdit(pkg, ruleType, index)) },
                    onLongClick = { pendingDelete = index },
                    end = {
                        Switch(
                            checked = rule.isEnable,
                            onCheckedChange = { UiState.toggleRule(pkg, ruleType, index, it) }
                        )
                        IconButton(onClick = { pendingDelete = index }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删除",
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        }
    }

    if (pendingDelete >= 0) {
        OverlayDialog(
            show = true,
            title = "删除这条规则？",
            summary = rules.getOrNull(pendingDelete)?.ad ?: "",
            onDismissRequest = { pendingDelete = -1 }
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { pendingDelete = -1 })
                TextButton(text = "删除", onClick = {
                    UiState.deleteRule(pkg, ruleType, pendingDelete)
                    pendingDelete = -1
                    Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}

/**
 * 沙僧：只对这一个应用生效的域名。
 *
 * 订阅里的黑名单是全局的（对所有应用生效），这里给「A 应用自己多拦几个域名」留位置，
 * 也是 APK 分析器产出域名的落点。
 */
@Composable
fun AppHostsScreen(ctx: Context, pkg: String) {
    val rev = UiState.revision
    val list = remember(pkg, rev) { UiState.appHostsOf(pkg) }
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    Kit.DetailPage(
        title = "此应用的域名",
        subtitle = pkg,
        onBack = { Navigator.back() },
        actionIcon = MiuixIcons.Add,
        actionDesc = "添加域名",
        onAction = { text = ""; adding = true }
    ) {
        item {
            Kit.Note("这里的域名只对 $pkg 生效（命中后解析到 127.0.0.1）。订阅同步下来的 ${UiState.hostCount} 条全局域名对所有应用生效。")
        }
        if (list.isEmpty()) {
            item {
                Kit.Empty(
                    text = "还没有为这个应用单独加域名",
                    actionText = "添加域名",
                    onAction = { text = ""; adding = true }
                )
            }
        } else {
            itemsIndexed(list) { _, host ->
                Kit.Item(
                    title = host,
                    summary = "解析到 127.0.0.1",
                    end = {
                        IconButton(onClick = { UiState.removeAppHost(pkg, host) }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删除",
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        }
    }

    if (adding) {
        OverlayDialog(
            show = true,
            title = "添加域名",
            summary = "一行一个，也可以用空格 / 逗号分隔，一次加多条",
            onDismissRequest = { adding = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "域名",
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    minLines = 5
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "取消", onClick = { adding = false })
                    TextButton(text = "添加", onClick = {
                        val n = UiState.addAppHosts(pkg, text)
                        adding = false
                        Toast.makeText(
                            ctx,
                            if (n > 0) "已添加 $n 个域名" else "没有可添加的域名",
                            Toast.LENGTH_SHORT
                        ).show()
                    })
                }
            }
        }
    }
}

/** 列表里只显示短名：全限定名太长会把行挤成三行 */
private fun shortNameOf(ad: String?): String {
    val s = ad?.trim().orEmpty()
    if (s.isBlank()) return "（未填主键）"
    return when {
        s == "*" -> "所有页面"
        s.endsWith(".Url") -> s.removeSuffix(".Url") + " · URL"
        s.endsWith(".Dialog") -> s.removeSuffix(".Dialog") + " · 弹框"
        s.endsWith(".Hook") -> s.removeSuffix(".Hook") + " · Hook"
        s.contains('.') -> s.substringAfterLast('.')
        else -> s
    }
}

/** 列表行的一行摘要：动作 + 延时 + 备注首行（截断，完整备注在编辑器里看） */
private fun compactSummary(rule: RuleItem): String = buildString {
    append(Actions.describe(rule.actionType))
    if (rule.delay > 0) append(" · 延时 ${rule.delay}ms")
    val remark = rule.displayRemark.substringBefore('\n').trim()
    if (remark.isNotBlank()) {
        append(" · ")
        append(if (remark.length > 12) remark.take(12) + "…" else remark)
    }
}

/**
 * 八戒任务列表。
 *
 * 任务由订阅同步过来，步骤目前只读展示（原版的步骤编辑要拖拽排序）；
 * 开关是「这条任务要不要执行」，比删掉更安全。
 */
@Composable
fun TaskListScreen(ctx: Context, pkg: String) {
    val rev = UiState.revision
    val tasks = remember(pkg, rev) { UiState.tasksOf(pkg) }
    var viewing by remember { mutableIntStateOf(-1) }
    var pendingDelete by remember { mutableIntStateOf(-1) }

    Kit.DetailPage(
        title = "八戒任务",
        subtitle = pkg,
        onBack = { Navigator.back() },
        actions = {
            TextButton(text = "新建", onClick = { Navigator.go(Dest.TaskEdit(pkg, -1)) })
        }
    ) {
        if (tasks.isEmpty()) {
            item {
                Kit.Empty(
                    text = "这个应用还没有八戒任务（订阅同步后会自动带过来）",
                    actionText = "去订阅",
                    onAction = { Navigator.jump(Dest.SettingsSubscription, Navigator.Tab.SETTINGS) }
                )
            }
        } else {
            item {
                Kit.Note("任务靠无障碍服务执行，需要在系统设置里开启「大圣净化」的无障碍开关。")
            }
            item {
                Kit.ArrowRow(
                    title = if (tasks.all { it.isEnable }) "全部停用" else "全部启用",
                    summary = "共 ${tasks.size} 个任务 · 点条目可看每个任务的步骤",
                    icon = MiuixIcons.Refresh,
                    onClick = {
                        UiState.setAllTasksEnabled(pkg, !tasks.all { it.isEnable })
                    }
                )
            }
            itemsIndexed(tasks) { index, task ->
                Kit.Item(
                    title = task.name?.takeIf { it.isNotBlank() } ?: (task.act ?: "任务"),
                    summary = buildString {
                        append("${task.steps.size} 步")
                        task.act?.let { append(" · ").append(it) }
                    },
                    onClick = { viewing = index },
                    end = {
                        Switch(
                            checked = task.isEnable,
                            onCheckedChange = { UiState.toggleTask(pkg, index, it) }
                        )
                        IconButton(onClick = { pendingDelete = index }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删除任务",
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        }
    }

    // 任务步骤只读详情：原版的步骤编辑要拖拽排序，这里先把「这条任务到底干嘛」讲清楚
    val shown = tasks.getOrNull(viewing)
    if (shown != null) {
        OverlayDialog(
            show = true,
            title = shown.name?.takeIf { it.isNotBlank() } ?: "任务步骤",
            onDismissRequest = { viewing = -1 }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                shown.act?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                shown.steps.forEachIndexed { i, step ->
                    Text(
                        text = "${i + 1}. ${stepSummary(step)}",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (shown.steps.isEmpty()) {
                    Text(
                        text = "这条任务没有步骤，订阅源里可能是空的。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "编辑步骤",
                        onClick = {
                            Navigator.go(Dest.TaskEdit(pkg, viewing))
                            viewing = -1
                        }
                    )
                    TextButton(text = "关闭", onClick = { viewing = -1 })
                }
            }
        }
    }

    if (pendingDelete >= 0) {
        OverlayDialog(
            show = true,
            title = "删除这个任务？",
            summary = tasks.getOrNull(pendingDelete)?.name ?: pkg,
            onDismissRequest = { pendingDelete = -1 }
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { pendingDelete = -1 })
                TextButton(text = "删除", onClick = {
                    UiState.deleteTask(pkg, pendingDelete)
                    pendingDelete = -1
                })
            }
        }
    }
}

/** 八戒步骤的一行人话：定位方式 + 延迟 */
private fun stepSummary(step: com.adhammer.rules.model.Step): String {
    val target = when {
        !step.clkTxt.isNullOrBlank() -> "点文本「${step.clkTxt}」"
        !step.clkId.isNullOrBlank() -> "点 id ${step.clkId}"
        !step.clkWv.isNullOrBlank() -> "点控件 ${step.clkWv}"
        step.sx0 >= 0 && step.sy1 >= 0 -> "滑动 (${step.sx0},${step.sy0}) → (${step.sx1},${step.sy1})"
        step.x >= 0 && step.y >= 0 -> "点坐标 (${step.x}, ${step.y})"
        !step.t.isNullOrBlank() -> "定位「${step.t}」"
        else -> "at=${step.at}"
    }
    return buildString {
        append(target)
        if (step.prs) append("（长按）")
        if (step.d > 0) append(" · 延迟 ${step.d}ms")
        if (step.c > 1) append(" · 重复 ${step.c} 次")
    }
}

/**
 * 规则编辑器：表单模式 + JSON 模式。
 * 表单模式面向日常编辑，JSON 模式面向「从别处抄一条规则直接粘进来」。
 */
@Composable
fun RuleEditScreen(ctx: Context, pkg: String, ruleType: Int, index: Int) {
    val existing = remember(index) { UiState.rulesOf(pkg, ruleType).getOrNull(index) }
    val options = remember(ruleType) { Actions.optionsFor(ruleType) }

    var actionType by remember { mutableIntStateOf(existing?.actionType ?: options.firstOrNull() ?: 1) }
    var ad by remember { mutableStateOf(existing?.ad ?: "") }
    var adKey by remember { mutableStateOf(existing?.adKey ?: "") }
    var delay by remember { mutableStateOf(existing?.delay?.toString() ?: "0") }
    var remark by remember { mutableStateOf(existing?.remark ?: "") }
    var enabled by remember { mutableStateOf(existing?.isEnable ?: true) }

    var json by remember { mutableStateOf(existing?.let { Jsonx.toJson(it) } ?: "") }
    var jsonMode by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = if (index < 0) "新增规则" else "编辑规则",
            subtitle = "${UiState.labelOf(pkg)} · ${typeNameOf(ruleType)}",
            canBack = true,
            onBack = { Navigator.back() },
            actions = {
                if (index >= 0) {
                    IconButton(onClick = { askDelete = true }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "删除",
                            tint = MiuixTheme.colorScheme.error
                        )
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Kit.Section("模式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "表单",
                        onClick = { jsonMode = false },
                        enabled = jsonMode
                    )
                    TextButton(
                        text = "JSON",
                        onClick = {
                            json = Jsonx.toJson(
                                RuleItem(
                                    actionType = actionType, ad = ad.ifBlank { null },
                                    adKey = adKey.ifBlank { null }, delay = delay.toLongOrNull() ?: 0,
                                    isEnable = enabled, remark = remark.ifBlank { null },
                                    ruleType = ruleType, pkg = pkg
                                )
                            ) ?: json
                            jsonMode = true
                        },
                        enabled = !jsonMode
                    )
                }
            }

            if (jsonMode) {
                item {
                    Kit.Section("规则 JSON")
                    Kit.Group {
                        Text(
                            text = "直接编辑整条规则，保存时会做格式校验。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        TextField(
                            value = json,
                            onValueChange = { json = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(260.dp),
                            minLines = 12
                        )
                    }
                }
            } else {
                item {
                    Kit.Section("动作")
                    Kit.Item(
                        title = Actions.describe(actionType),
                        summary = "at=$actionType · 点这里换动作",
                        onClick = { showActions = true }
                    )
                }

                item {
                    Kit.Section("主键")
                    Kit.Group {
                        Text(
                            text = Actions.adHint(ruleType, actionType),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        TextField(
                            value = ad,
                            onValueChange = { ad = it },
                            label = "ad",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }

                item {
                    Kit.Section("规则值")
                    Kit.Group {
                        Text(
                            text = Actions.adKeyHint(ruleType, actionType),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        TextField(
                            value = adKey,
                            onValueChange = { adKey = it },
                            label = "adKey",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(120.dp),
                            minLines = 4
                        )
                    }
                }

                item {
                    Kit.Section("其它")
                    Kit.Group {
                        TextField(
                            value = delay,
                            onValueChange = { delay = it.filter { c -> c.isDigit() } },
                            label = "延时（毫秒）",
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = remark,
                            onValueChange = { remark = it },
                            label = "备注",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        Row(modifier = Modifier.padding(top = 12.dp)) {
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                            Text(
                                text = "启用这条规则",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Kit.H_PAD, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = {
                            val ok = if (jsonMode) saveFromJson(pkg, ruleType, index, json)
                            else UiState.saveRule(
                                pkg, ruleType, index,
                                RuleItem(
                                    actionType = actionType,
                                    ad = ad.ifBlank { null },
                                    adKey = adKey.ifBlank { null },
                                    delay = delay.toLongOrNull() ?: 0,
                                    isEnable = enabled,
                                    remark = remark.ifBlank { null },
                                    ruleType = ruleType,
                                    pkg = pkg
                                )
                            )
                            Toast.makeText(
                                ctx,
                                if (ok) "已保存，目标 App 下次启动生效" else "保存失败（JSON 格式不对？）",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (ok) Navigator.back()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "保存")
                    }
                    TextButton(text = "取消", onClick = { Navigator.back() })
                }
            }
        }
    }

    if (showActions) {
        OverlayDialog(
            show = true,
            title = "选择动作",
            onDismissRequest = { showActions = false }
        ) {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                itemsIndexed(options) { _, at ->
                    Kit.Item(
                        title = Actions.describe(at),
                        summary = "at=$at",
                        onClick = {
                            actionType = at
                            showActions = false
                        }
                    )
                }
            }
        }
    }

    if (askDelete) {
        OverlayDialog(
            show = true,
            title = "删除这条规则？",
            onDismissRequest = { askDelete = false }
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { askDelete = false })
                TextButton(text = "删除", onClick = {
                    UiState.deleteRule(pkg, ruleType, index)
                    askDelete = false
                    Navigator.back()
                })
            }
        }
    }
}

private fun saveFromJson(pkg: String, ruleType: Int, index: Int, json: String): Boolean {
    val item = runCatching { Jsonx.fromJson<RuleItem>(json) }.getOrNull() ?: return false
    return UiState.saveRule(pkg, ruleType, index, item.copy(ruleType = ruleType, pkg = pkg))
}

/** 单个 App 的元信息：首页 Activity（at=3/4 要用）+ 跳过提示文案（adTips） */
@Composable
fun HomeActScreen(ctx: Context, pkg: String) {
    var value by remember { mutableStateOf(UiState.homeOf(pkg)) }
    var tips by remember { mutableStateOf(UiState.tipsOf(pkg)) }
    val launcher = remember(pkg) {
        runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull() ?: ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = "应用设置",
            subtitle = pkg,
            canBack = true,
            onBack = { Navigator.back() }
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Kit.Note("at=3（跳过到首页）和 at=4（改写跳转目标）需要知道「该跳到哪」。订阅里没这个字段，所以在这里补上。留空则降级为直接关闭广告页。")
            }
            item {
                Kit.Group {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        label = "首页 Activity 全名",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(text = "填启动页", onClick = { value = launcher })
                        TextButton(text = "清空", onClick = { value = "" }, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (launcher.isNotBlank()) {
                item { Kit.Note("系统记录的启动页：$launcher") }
            }

            item { Kit.Section("跳过提示") }
            item {
                Kit.Group {
                    TextField(
                        value = tips,
                        onValueChange = { tips = it },
                        label = "提示文案",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        TextButton(
                            text = "用默认",
                            onClick = { tips = com.adhammer.hook.SkipTips.DEFAULT_TEXT }
                        )
                        TextButton(
                            text = "不提示",
                            onClick = { tips = "" },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            item { Kit.Note("留空 = 不弹提示；全局开关在「设置 → 净化行为 → 跳过时提示」。") }

            item {
                Row(modifier = Modifier.padding(horizontal = Kit.H_PAD)) {
                    top.yukonga.miuix.kmp.basic.Button(onClick = {
                        UiState.setHome(pkg, value)
                        UiState.setTips(pkg, tips)
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        Navigator.back()
                    }) { Text(text = "保存") }
                }
            }
        }
    }
}
