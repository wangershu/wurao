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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
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
        Kit.Bar(
            title = UiState.labelOf(pkg),
            subtitle = pkg,
            canBack = true,
            onBack = { Navigator.back() },
            actions = {
                // 首页 Activity：at=3/4 跳过到首页时必须告诉模块「首页是哪个」
                IconButton(onClick = { Navigator.go(Dest.HomeAct(pkg)) }) {
                    Icon(imageVector = Icons.Default.Home, contentDescription = "首页 Activity")
                }
                IconButton(onClick = { Navigator.go(Dest.RuleEdit(pkg, RULE_TYPES[tab], -1)) }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "新增规则")
                }
                IconButton(onClick = { askClear = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清空这个应用的规则",
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }
        )

        // 应用级开关：关掉后这个 app 一条 hook 都不装（白名单）
        Kit.SwitchItem(
            title = "对这个应用生效",
            summary = if (UiState.appDisabled(pkg)) "已停用：全部规则都不执行"
            else "该应用的规则正常执行（重启 App 生效）",
            checked = !UiState.appDisabled(pkg),
            onChecked = { UiState.setAppDisabled(pkg, !it) }
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
                    title = rule.ad?.takeIf { it.isNotBlank() } ?: "（未填主键）",
                    summary = ruleSummary(rule.actionType, rule.ad, rule.displayRemark, rule.delay),
                    icon = Icons.Default.Edit,
                    onClick = { Navigator.go(Dest.RuleEdit(pkg, type, index)) },
                    onLongClick = { pendingDelete = index },
                    end = {
                        Switch(
                            checked = rule.isEnable,
                            onCheckedChange = { UiState.toggleRule(pkg, type, index, it) }
                        )
                        IconButton(onClick = { pendingDelete = index }) {
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

@Composable
private fun TaskList(pkg: String) {
    val tasks = UiState.tasksOf(pkg)
    if (tasks.isEmpty()) {
        Kit.Empty(text = "这个应用还没有八戒任务（订阅同步后会自动带过来）")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = Kit.pad(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(tasks) { _, task ->
            Kit.Item(
                title = task.name?.takeIf { it.isNotBlank() } ?: (task.act ?: "任务"),
                summary = buildString {
                    append("${task.steps.size} 步")
                    task.act?.let { append(" · $it") }
                    task.remark?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                },
                onClick = null,
                end = {
                    Text(
                        text = if (task.isEnable) "启用" else "停用",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            )
        }
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
                            imageVector = Icons.Default.Delete,
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

/** 首页 Activity 配置：at=3 跳过到首页 / at=4 改写跳转时要用 */
@Composable
fun HomeActScreen(ctx: Context, pkg: String) {
    var value by remember { mutableStateOf(UiState.homeOf(pkg)) }
    val launcher = remember(pkg) {
        runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull() ?: ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Kit.Bar(
            title = "首页 Activity",
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
            item {
                Row(modifier = Modifier.padding(horizontal = Kit.H_PAD)) {
                    top.yukonga.miuix.kmp.basic.Button(onClick = {
                        UiState.setHome(pkg, value)
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        Navigator.back()
                    }) { Text(text = "保存") }
                }
            }
        }
    }
}
