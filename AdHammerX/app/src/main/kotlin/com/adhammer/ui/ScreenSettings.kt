package com.adhammer.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.analyzer.AdFeatures
import com.adhammer.rules.Cfg
import com.adhammer.rules.RuleStore
import com.adhammer.rules.Subscriber
import com.adhammer.rules.model.RuleTypes
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置根页：只放分类入口，具体配置都在二级页里。
 *
 * 分类方式对齐 HyperIsland 的 SettingsPage —— 一级页是目录，二级页才是开关和输入框。
 */
@Composable
fun SettingsScreen(ctx: Context, onExport: () -> Unit, onImport: () -> Unit) {
    Kit.Page(title = "设置") {
        item { Kit.Section("净化") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "净化行为",
                    summary = "三个子系统的开关：页面净化 / 自动跳过 / 域名拦截",
                    icon = MiuixIcons.Blocklist,
                    onClick = { Navigator.go(Dest.SettingsPurify) }
                )
                Kit.ArrowRow(
                    title = "自动跳过",
                    summary = "不写规则也能点掉开屏广告 · ${UiState.skipScope.size} 个应用",
                    icon = MiuixIcons.Refresh,
                    onClick = { Navigator.go(Dest.SkipList) }
                )
                Kit.ArrowRow(
                    title = "弹框拦截",
                    summary = "升级 / 青少年模式 / 运营弹窗",
                    icon = MiuixIcons.Blocklist,
                    onClick = { Navigator.go(Dest.Popups) }
                )
                Kit.ArrowRow(
                    title = "文件替换",
                    summary = "把广告缓存换成空文件（有风险）",
                    icon = MiuixIcons.Delete,
                    onClick = { Navigator.go(Dest.Replace) }
                )
                Kit.ArrowRow(
                    title = "沙僧代理",
                    summary = "系统 VPN 拦广告域名，对所有应用生效",
                    icon = MiuixIcons.Link,
                    onClick = { Navigator.go(Dest.SengVpn) }
                )
                Kit.ArrowRow(
                    title = "悟空实验室",
                    summary = "填 Activity / id，看会命中哪些规则",
                    icon = MiuixIcons.Tune,
                    onClick = { Navigator.go(Dest.Lab) }
                )
                Kit.ArrowRow(
                    title = "订阅与规则源",
                    summary = "订阅地址、广告特征库、AI 接口",
                    icon = MiuixIcons.Link,
                    onClick = { Navigator.go(Dest.SettingsSubscription) }
                )
            }
        }

        item { Kit.Section("通用") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "外观",
                    summary = "主题：${UiPrefs.themeMode.label} · 底栏：${if (UiPrefs.floatingBar) "悬浮" else "贴底"}",
                    icon = MiuixIcons.Theme,
                    onClick = { Navigator.go(Dest.SettingsAppearance) }
                )
                Kit.ArrowRow(
                    title = "高级",
                    summary = "作用域、权限、框架信息",
                    icon = MiuixIcons.Tune,
                    onClick = { Navigator.go(Dest.SettingsAdvanced) }
                )
            }
        }

        item { Kit.Section("数据") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "备份与恢复",
                    summary = "规则、任务、首页配置、域名、统计",
                    icon = MiuixIcons.Backup,
                    onClick = { Navigator.go(Dest.SettingsBackup) }
                )
            }
        }

        item { Kit.Section("其他") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "使用帮助",
                    summary = "从启用模块到验证生效",
                    icon = MiuixIcons.Help,
                    onClick = { Navigator.go(Dest.SettingsHelp) }
                )
                Kit.ArrowRow(
                    title = "关于",
                    summary = "版本、开发者、项目链接",
                    icon = MiuixIcons.Info,
                    onClick = { Navigator.go(Dest.About) }
                )
            }
        }
    }
}

/** 净化行为：三个子系统分别开关 + 总暂停 */
@Composable
fun PurifySettingsScreen() {
    Kit.DetailPage(title = "净化行为", onBack = { Navigator.back() }) {
        item { Kit.Note("hook 在目标进程启动时才安装，改完开关要重启目标应用才会生效。") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "暂停全部净化",
                    summary = "打开后一条 hook 都不装，等于临时关掉整个模块",
                    icon = MiuixIcons.Blocklist,
                    checked = UiState.pause,
                    onChecked = { UiState.setFlag(Cfg.PAUSE, it) }
                )
            }
        }

        item { Kit.Section("子系统") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "页面净化",
                    summary = "应用内去广告：Activity / URL / View / 组件 / 文件替换",
                    icon = MiuixIcons.Blocklist,
                    enabled = !UiState.pause,
                    checked = UiState.wuKongOn,
                    onChecked = { UiState.setFlag(Cfg.WU_KONG, it) }
                )
                Kit.SwitchRow(
                    title = "自动跳过",
                    summary = "无障碍自动点击（跳过按钮、关弹窗）",
                    icon = MiuixIcons.Refresh,
                    enabled = !UiState.pause,
                    checked = UiState.baJieOn,
                    onChecked = { UiState.setFlag(Cfg.BA_JIE, it) }
                )
                Kit.SwitchRow(
                    title = "域名拦截",
                    summary = "广告域名解析到 127.0.0.1",
                    icon = MiuixIcons.Link,
                    enabled = !UiState.pause,
                    checked = UiState.shaSengOn,
                    onChecked = { UiState.setFlag(Cfg.SHA_SENG, it) }
                )
            }
        }

        item { Kit.Section("反馈") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "跳过时提示",
                    summary = "成功跳过广告后弹一句 Toast，文案可在应用详情里按 App 自定义",
                    icon = MiuixIcons.Ok,
                    checked = Cfg.get(UiState.local, Cfg.TIPS, true),
                    onChecked = { UiState.setFlag(Cfg.TIPS, it) }
                )
            }
        }
    }
}

/** 外观：主题 + 底栏形态 */
@Composable
fun AppearanceSettingsScreen(ctx: Context) {
    Kit.DetailPage(title = "外观", onBack = { Navigator.back() }) {
        item { Kit.Section("主题") }
        item { Kit.Note("默认跟随系统，改完立即生效，不用重启。") }
        item {
            Kit.RadioGroup(
                items = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(UiPrefs.themeMode),
                onSelect = { UiPrefs.setThemeMode(ctx, ThemeMode.entries[it]) }
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
                    onChecked = { UiPrefs.setFloatingBar(ctx, it) }
                )
            }
        }
    }
}

/** 订阅与规则源：规则从哪来 */
@Composable
fun SubscriptionSettingsScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var subUrl by remember {
        mutableStateOf(RuleStore.getMetaUrl(UiState.local) ?: Subscriber.DEFAULT_SUB_URL)
    }
    var featUrl by remember { mutableStateOf("https://dy.940622.xyz/ad_features.json") }
    var featJson by remember { mutableStateOf("") }
    var featMsg by remember { mutableStateOf("") }
    val feat = AdFeatures.get(ctx)

    Kit.DetailPage(title = "订阅与规则源", onBack = { Navigator.back() }) {
        item { Kit.Section("规则订阅") }
        item {
            Kit.Group {
                Text(
                    text = "订阅是 zip 包：悟空规则 / 八戒任务 / 沙僧域名一次性下发。同步是整包覆盖，不会冲掉你手写的规则。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                TextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = "订阅地址",
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    TextButton(
                        text = if (UiState.syncing) "同步中…" else "立即同步",
                        enabled = !UiState.syncing,
                        onClick = { scope.launch { UiState.sync(ctx, subUrl) } }
                    )
                }
                if (UiState.lastMessage.isNotBlank()) {
                    Text(
                        text = UiState.lastMessage,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item { Kit.Section("广告特征库", "v${feat.version} · ${feat.source}") }
        item {
            Kit.Group {
                Text(
                    text = "APK 分析器的「知识」都在这份 JSON 里：SDK 类名、可疑组件名、资源名、域名关键词。规则要长期维护，维护的其实是它。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Kit.KeyValue("SDK 条目", "${feat.sdks.size}")
                Kit.KeyValue("资源名关键词", "${feat.resWords.size}")
                Kit.KeyValue("域名关键词", "${feat.domainWords.size}")
                if (feat.updateTime.isNotBlank()) Kit.KeyValue("更新时间", feat.updateTime)

                TextField(
                    value = featUrl,
                    onValueChange = { featUrl = it },
                    label = "特征库地址",
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    TextButton(text = "在线更新", onClick = {
                        scope.launch {
                            featMsg = "下载中…"
                            featMsg = if (AdFeatures.fetchUpdate(ctx, featUrl)) "特征库已更新" else "下载或解析失败"
                        }
                    })
                    TextButton(
                        text = "恢复内置",
                        onClick = { AdFeatures.reset(ctx); featMsg = "已恢复内置特征库" },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                TextField(
                    value = featJson,
                    onValueChange = { featJson = it },
                    label = "粘贴 JSON 导入",
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(90.dp),
                    minLines = 3
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(text = "导入", onClick = {
                        featMsg = if (AdFeatures.update(ctx, featJson)) "导入成功" else "JSON 格式不对"
                    })
                }
                if (featMsg.isNotBlank()) {
                    Text(
                        text = featMsg,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item { Kit.Section("AI 自动写规则") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "AI 接口配置",
                    summary = "可选：不填也能用本地生成与 APK 分析",
                    icon = MiuixIcons.Update,
                    onClick = { Navigator.go(Dest.Factory) }
                )
            }
        }
    }
}

/** 备份与恢复 */
@Composable
fun BackupSettingsScreen(ctx: Context, onExport: () -> Unit, onImport: () -> Unit) {
    var msg by remember { mutableStateOf("") }

    Kit.DetailPage(title = "备份与恢复", onBack = { Navigator.back() }) {
        item { Kit.Section("导出 / 导入") }
        item {
            Kit.Group {
                Text(
                    text = "导出的是一个 JSON 文件，包含规则、任务、首页配置、域名和统计数据。导入只吃认识的字段，不会误伤其它配置。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    TextButton(text = "导出到文件", onClick = onExport)
                    TextButton(text = "从文件导入", onClick = onImport, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        item { Kit.Section("清理") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "清空拦截记录",
                    summary = "只清日志明细，统计计数保留",
                    icon = MiuixIcons.Delete,
                    onClick = {
                        UiState.clearLogs()
                        msg = "已清空拦截记录"
                        Toast.makeText(ctx, "已清空拦截记录", Toast.LENGTH_SHORT).show()
                    }
                )
                Kit.ArrowRow(
                    title = "清空统计数据",
                    summary = "计数归零，规则不受影响",
                    icon = MiuixIcons.Delete,
                    onClick = {
                        UiState.clearStats()
                        msg = "已清空统计数据"
                        Toast.makeText(ctx, "已清空统计数据", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        if (msg.isNotBlank()) item { Kit.Note(msg) }

        item { Kit.Section("当前数据量") }
        item {
            Kit.Group {
                Kit.KeyValue("已适配应用", "${UiState.appIndex.size}")
                Kit.KeyValue("规则条数", "${UiState.appIndex.sumOf { it.second }}")
                Kit.KeyValue("八戒任务", "${UiState.taskCount}")
                Kit.KeyValue("拦截域名", "${UiState.hostCount}")
                Kit.KeyValue("累计拦截", "${UiState.statTotal}")
            }
        }
    }
}

/** 高级：作用域、权限、框架信息 */
@Composable
fun AdvancedSettingsScreen(ctx: Context) {
    Kit.DetailPage(title = "高级", onBack = { Navigator.back() }) {
        item { Kit.Section("作用域") }
        item {
            Kit.Group {
                Text(
                    text = "目标应用必须在框架的作用域里，hook 才会装进去。已适配的应用可以一键申请。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        items(
            items = UiState.appIndex.take(12),
            key = { it.first }
        ) { entry ->
            val pkg = entry.first
            Kit.ArrowItem(
                title = UiState.labelOf(pkg),
                summary = pkg,
                onClick = {
                    UiState.requestScope(pkg)
                    Toast.makeText(ctx, "已请求加入作用域：$pkg", Toast.LENGTH_SHORT).show()
                }
            )
        }

        item { Kit.Section("权限") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "悬浮窗权限",
                    summary = if (com.adhammer.float.FloatController.canOverlay(ctx)) "已授予" else "未授予",
                    icon = MiuixIcons.Tune,
                    onClick = { com.adhammer.float.FloatController.toggle(ctx, true) }
                )
            }
        }

        item { Kit.Section("框架信息") }
        item {
            Kit.Group {
                Kit.KeyValue("状态", if (UiState.active) "已连接" else "未连接")
                Kit.KeyValue("框架", UiState.framework)
                Kit.KeyValue("Hook API", "LSPosed 102（libxposed）")
            }
        }
    }
}

/** 使用帮助 */
@Composable
fun HelpSettingsScreen() {
    Kit.DetailPage(title = "使用帮助", onBack = { Navigator.back() }) {
        item { Kit.Section("启用步骤") }
        item {
            Kit.Group {
                HelpStep("1", "安装并打开本模块，在框架（LSPosed）里启用它。")
                HelpStep("2", "勾选作用域：主页提示「未检测到框架」时，去高级页按应用申请，或在框架里手动勾选。")
                HelpStep("3", "强制停止目标应用再重新打开 —— hook 只在进程启动时安装。")
                HelpStep("4", "回到主页：状态卡显示「已激活」才算接通；显示「未激活」时看下面那条黄色提示写了什么原因。")
                HelpStep("5", "打开目标应用逛一圈，再回统计页看有没有命中记录。")
            }
        }

        item { Kit.Section("规则从哪来") }
        item {
            Kit.Group {
                HelpStep("订阅", "设置 → 订阅与规则源 → 立即同步。覆盖面最大，一次几百个应用。")
                HelpStep("手工", "应用 → 选应用 → 按分类改规则，可逐条开关和编辑。")
                HelpStep("分析", "应用 → APK 分析器，不联网就能挖出一个 App 的广告组件。")
                HelpStep("快照", "开悬浮窗停在广告页抓快照 → 规则工厂生成。")
            }
        }

        item { Kit.Section("没生效怎么办") }
        item {
            Kit.Group {
                Text(
                    text = "① 确认作用域勾了且重启过目标应用；② 打开「拦截记录」看有没有命中，" +
                        "一条都没有说明规则没匹配上；③ 规则的主键（Activity / View id）对不对，" +
                        "可以用 APK 分析器重新扫一遍；④ 开屏广告多数是 Activity，用「拦截启动」比「禁用组件」稳。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun HelpStep(index: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = index,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 规则类型展示名（详情页 Tab 用） */
fun typeLabel(ruleType: Int): String = when (ruleType) {
    RuleTypes.WU_KONG_RULE -> "通用"
    RuleTypes.WU_KONG_URL -> "URL"
    RuleTypes.WU_KONG_VIEW -> "View"
    RuleTypes.WU_KONG_COMPONENT -> "组件"
    RuleTypes.WU_KONG_REPLACE -> "文件"
    RuleTypes.BA_JIE_TASK -> "任务"
    else -> "其他"
}
