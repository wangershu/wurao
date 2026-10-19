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
import com.adhammer.rules.Jsonx
import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RuleTypes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val RULE_TABS = listOf("通用", "URL", "View", "组件", "文件", "任务")
private val RULE_TYPES = listOf(
    RuleTypes.WU_KONG_RULE, RuleTypes.WU_KONG_URL, RuleTypes.WU_KONG_VIEW,
    RuleTypes.WU_KONG_COMPONENT, RuleTypes.WU_KONG_REPLACE, RuleTypes.BA_JIE_TASK
)

/**
 * 应用详情：按规则类型分组，条目可开关、可编辑、可删除 —— 复刻原版「悟空规则可编辑」的能力。
 */
@Composable
fun AppDetailScreen(ctx: Context, pkg: String) {
    var tab by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableIntStateOf(-1) }
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
                IconButton(onClick = { Navigator.go(Dest.RuleEdit(pkg, RULE_TYPES[tab], -1)) }) {
                    Icon(imageVector = MiuixIcons.Add, contentDescription = "新增规则")
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

        TabRow(
            tabs = RULE_TABS,
            selectedTabIndex = tab,
            onTabSelected = { tab = it },
            modifier = Modifier.padding(horizontal = Kit.H_PAD, vertical = 8.dp)
        )

        val type = RULE_TYPES[tab]

        if (type == RuleTypes.BA_JIE_TASK) {
            TaskList(pkg)
            return@Column
        }

        val rules = UiState.rulesOf(pkg, type)
        if (rules.isEmpty()) {
            Kit.Empty(
                text = "这一类还没有规则，右上角 + 可以手动加一条",
                actionText = "新增规则",
                onAction = { Navigator.go(Dest.RuleEdit(pkg, type, -1)) }
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(rules) { index, rule ->
                Kit.Item(
                    title = shortNameOf(rule.ad),
                    summary = compactSummary(rule),
                    onClick = { Navigator.go(Dest.RuleEdit(pkg, type, index)) },
                    onLongClick = { pendingDelete = index },
                    end = {
                        Switch(
                            checked = rule.isEnable,
                            onCheckedChange = { UiState.toggleRule(pkg, type, index, it) }
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
            summary = UiState.rulesOf(pkg, RULE_TYPES[tab]).getOrNull(pendingDelete)?.ad ?: "",
            onDismissRequest = { pendingDelete = -1 }
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { pendingDelete = -1 })
                TextButton(text = "删除", onClick = {
                    UiState.deleteRule(pkg, RULE_TYPES[tab], pendingDelete)
                    pendingDelete = -1
                    Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    if (askClear) {
        OverlayDialog(
            show = true,
            title = "清空这个应用的全部规则？",
            summary = "$pkg\n规则和任务都会被删掉，订阅同步后会重新拉回来。",
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

@Composable
private fun TaskList(pkg: String) {
    val tasks = UiState.tasksOf(pkg)
    var viewing by remember { mutableIntStateOf(-1) }
    var pendingDelete by remember { mutableIntStateOf(-1) }

    if (tasks.isEmpty()) {
        Kit.Empty(text = "这个应用还没有八戒任务（订阅同步后会自动带过来）")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = Kit.pad(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Kit.ArrowRow(
                title = if (tasks.all { it.isEnable }) "全部停用" else "全部启用",
                summary = "共 ${tasks.size} 个任务 · 点开可看每个任务的步骤",
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
