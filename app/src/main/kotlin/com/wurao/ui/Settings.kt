package com.wurao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wurao.core.Cfg
import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.store.RuleStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置：平铺一张列表（评审拍板：不加小分类）。
 * 顺序也是拍板的：全部暂停在最顶（开关），关于沉底。
 */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var paused by remember {
        mutableStateOf(Ui.prefs(ctx).getString(Cfg.KEY_PAUSED, null) == "true")
    }
    var confirmPause by remember { mutableStateOf(false) }

    Kit.Page(title = "设置") {
        item {
            // 破坏性操作放最顶上：开关即状态，开之前确认一次
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "全部暂停",
                    summary = if (paused) "现在所有拦截都停着" else "一键停掉所有拦截（规则保留，随时恢复）",
                    icon = MiuixIcons.Pause,
                    checked = paused,
                    onChecked = { on ->
                        if (on) {
                            confirmPause = true
                        } else {
                            paused = false
                            Ui.prefs(ctx).edit().putString(Cfg.KEY_PAUSED, "false").apply()
                            Ui.pushRulesAsync(ctx)
                        }
                    },
                )
            }
        }
        item {
            Kit.ArrowItem(
                title = "订阅与规则源",
                summary = "导入订阅 / 域名表，管理我自己的规则",
                icon = MiuixIcons.Download,
                onClick = { Navigator.go(Dest.Sources) },
            )
        }
        item {
            Kit.ArrowItem(
                title = "备份与恢复",
                summary = "导出成 JSON 文件存着，换手机导回来",
                icon = MiuixIcons.Backup,
                onClick = { Navigator.go(Dest.Backup) },
            )
        }
        item {
            Kit.ArrowItem(
                title = "外观",
                summary = "主题、悬浮底栏",
                icon = MiuixIcons.Theme,
                onClick = { Navigator.go(Dest.Appearance) },
            )
        }
        item {
            Kit.ArrowItem(
                title = "关于",
                summary = "版本、开发者、项目链接",
                icon = MiuixIcons.Info,
                onClick = { Navigator.go(Dest.About) },
            )
        }
    }

    ConfirmDialog(
        show = confirmPause,
        title = "全部暂停？",
        text = "所有拦截都会停掉（规则保留）。把开关关回去就恢复。",
        confirmText = "暂停",
        onDismiss = { confirmPause = false },
        onConfirm = {
            paused = true
            Ui.prefs(ctx).edit().putString(Cfg.KEY_PAUSED, "true").apply()
            Ui.pushRulesAsync(ctx)
        },
    )
}

/** miuix 风格的确认弹窗（0.9.4 的 OverlayDialog 签名不稳定，这里自绘一个同款观感的） */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!show) return
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    TextButton(
                        text = confirmText,
                        onClick = { onConfirm(); onDismiss() },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

// ==================== 订阅与规则源 ====================

/**
 * 订阅与规则源（二级页）：导入 + 我的规则，合一页。
 *
 * 导入报告**不静默**：跳过了多少、为什么跳过，都要写进报告给你看。
 */
@Composable
fun SourcesScreen() {
    val ctx = LocalContext.current
    val rev = Ui.ruleRev
    var rules = remember(rev) { RuleStore.all() }
    var subText by remember { mutableStateOf("") }
    var subReport by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf("") }
    var act by remember { mutableStateOf(Act.HIDE) }
    var tip by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<Rule?>(null) }

    fun save() {
        Ui.pushRulesAsync(ctx)
    }

    fun add() {
        if (pkg.isBlank() || sel.isBlank()) {
            tip = "包名和选择器都要填"
            return
        }
        val r = Rule(
            domain = Domain.NODE,
            target = sel.trim(),
            act = act,
            scope = if (pkg.trim() == "*") Scope.Global else Scope.App(pkg.trim()),
            origin = Origin.User,
            groupKey = Cfg.GROUP_USER,
            ruleKey = sel.trim().hashCode().toString(),
            note = sel.trim(),
        )
        val issues = r.validate()
        if (issues.isNotEmpty()) {
            tip = "这条规则不合法：" + issues.joinToString("；")
            return
        }
        RuleStore.upsert(r)
        save()
        sel = ""
        tip = "已加（共 ${RuleStore.all().size} 条）"
    }

    Kit.DetailPage(title = "订阅与规则源", onBack = { Navigator.back() }) {
        // ---------- 导入 ----------
        item { Kit.Section("导入") }
        item {
            Kit.Note(
                "粘贴订阅内容或域名表，或填网址（联网下载）。是哪种格式自己认，不用选。\n" +
                    "反复导入同一份不会翻倍，更新订阅也不会动你自己抓的规则。"
            )
        }
        item {
            Kit.Group {
                TextField(
                    value = subText,
                    onValueChange = { subText = it },
                    label = "订阅内容或网址",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(text = "导入", onClick = {
                        val t = subText.trim()
                        if (t.isBlank()) {
                            subReport = "先粘贴内容或填网址"
                            return@TextButton
                        }
                        if (t.startsWith("http")) {
                            subReport = "下载中…"
                            Ui.importSourceUrl(ctx, t) { subReport = it }
                        } else {
                            subReport = Ui.importSource(ctx, t)
                        }
                    })
                    TextButton(
                        text = "清空订阅",
                        onClick = {
                            Ui.prefs(ctx).edit().remove(Cfg.KEY_SUB_RULES).apply()
                            CfgPush.flush(ctx)
                            subReport = "已清空订阅规则（你自己抓的不受影响）"
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (subReport.isNotEmpty()) {
                    Text(
                        subReport,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // ---------- 我的规则（手写） ----------
        item { Kit.Section("我的规则") }
        item {
            Kit.Note(
                "自己加的规则优先级最高 —— 你是亲眼看到那个东西才加的，比订阅来的更可信。\n" +
                    "例：藏掉美团底栏的视频入口 → 包名 com.sankuai.meituan，" +
                    "选择器 [vid=\"bottom_tab_block\"] << [desc=\"视频\"]"
            )
        }
        item {
            Kit.Group {
                TextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = "包名（* 表示所有应用）",
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = sel,
                    onValueChange = { sel = it },
                    label = "选择器",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                // 动作选择：胶囊按钮组
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Act.entries.filter { it.allows(Domain.NODE) }.forEach { a ->
                        val on = act == a
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (on) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceVariant
                                )
                                .clickable { act = a }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                actCn(a),
                                fontSize = 12.sp,
                                color = if (on) androidx.compose.ui.graphics.Color.White
                                else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(0.dp),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "加一条", onClick = { add() })
                }
                if (tip.isNotEmpty()) {
                    Text(
                        tip,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ---------- 我的规则清单 ----------
        // 规则按来源分组显示。**不能逐条铺开**：库里两万条（大半是广告域名），
        // 一次性组合两万个 Card 会把界面压死。只有你自己抓的那几条才逐条列。
        val mine = rules.filter { it.origin is Origin.User }
        val groups = rules.filter { it.origin !is Origin.User }
            .groupBy { r ->
                when (val o = r.origin) {
                    is Origin.Subscription -> "订阅#" + o.subsId + "：" + r.groupKey
                    is Origin.Seed -> "种子 ${o.name}：" + r.groupKey
                    Origin.Builtin -> "内置：" + r.groupKey
                    Origin.User -> "自抓：" + r.groupKey
                }
            }

        if (mine.isNotEmpty()) {
            item { Kit.Section("我自己抓的", "${mine.size} 条") }
            items(mine, key = { it.id }) { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.target, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                            val p = when (val sc = r.scope) {
                                is Scope.App -> sc.pkg
                                is Scope.Component -> sc.pkg
                                Scope.Global -> "所有应用"
                            }
                            Text(
                                "$p · ${actCn(r.act)} · ${domainCn(r.domain.key)}",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        IconButton(onClick = { confirmDel = r }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删除这条规则",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                }
            }
        }

        if (groups.isNotEmpty()) {
            item { Kit.Section("订阅与导入", "${groups.values.sumOf { it.size }} 条，合并显示") }
            items(groups.entries.toList(), key = { it.key }) { (name, list) ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text(
                                "${list.size} 条",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        // 整份删掉：订阅不想要了，一次清干净
                        IconButton(onClick = {
                            list.forEach { RuleStore.remove(it.id) }
                            save()
                        }) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删掉这一整份",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            item { Kit.Empty("还没有规则", "去应用页抓屏挑控件") { Navigator.switch(Navigator.Tab.APPS) } }
        }
    }

    ConfirmDialog(
        show = confirmDel != null,
        title = "删掉这条规则？",
        text = confirmDel?.target ?: "",
        confirmText = "删掉",
        onDismiss = { confirmDel = null },
        onConfirm = {
            confirmDel?.let { RuleStore.remove(it.id) }
            save()
        },
    )
}

// ==================== 备份与恢复 ====================

/** 备份与恢复（对齐原版结构；导出物从「一段文本」改成 JSON 文件 —— 评审拍板） */
@Composable
fun BackupScreen() {
    Kit.DetailPage(title = "备份与恢复", onBack = { Navigator.back() }) {
        item { Kit.Section("导出 / 恢复") }
        item {
            Kit.Group {
                Text(
                    "导出的是一个 JSON 文件，包含规则、域名、开关等全部配置。" +
                        "存到网盘或发给新手机，在那边「从文件导入」即可。恢复是整份覆盖，会先告诉你里面有多少项。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    TextButton(text = "导出到文件", onClick = { BackupBridge.onExportClick() })
                    TextButton(
                        text = "从文件导入",
                        onClick = { BackupBridge.onImportClick() },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        item { Kit.Section("当前数据量") }
        item {
            Kit.Group {
                val all = run {
                    Ui.ruleRev
                    RuleStore.all()
                }
                Kit.KeyValue("规则条数", "${all.size}")
                Kit.KeyValue("其中订阅导入", "${all.count { it.origin !is Origin.User }}")
                Kit.KeyValue("命中记录", "${HitStore.all().size}")
                Kit.KeyValue("拦截域名", "${all.count { it.domain == Domain.HOST }}")
            }
        }
    }
}

// ==================== 外观 ====================

/** 外观（对齐原版 AppearanceSettingsScreen：主题 RadioGroup + 悬浮底栏 SwitchRow） */
@Composable
fun AppearanceScreen() {
    val ctx = LocalContext.current
    Kit.DetailPage(title = "外观", onBack = { Navigator.back() }) {
        item { Kit.Section("主题") }
        item { Kit.Note("默认跟随系统，改完立即生效，不用重启。") }
        item {
            Kit.RadioGroup(
                items = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(UiPrefs.themeMode),
                onSelect = { UiPrefs.setThemeMode(ctx, ThemeMode.entries[it]) },
            )
        }

        item { Kit.Section("底栏") }
        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "悬浮底栏",
                    summary = "关掉后变成贴底的整条底栏",
                    icon = MiuixIcons.Tune,
                    checked = UiPrefs.floatingBar,
                    onChecked = { UiPrefs.setFloatingBar(ctx, it) },
                )
            }
        }
    }
}
