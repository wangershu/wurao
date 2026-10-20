package com.adhammer.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.Step
import com.adhammer.rules.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ============================================================================
// 原版 APK 里几个独立功能页的复刻
//
// 参考原版 UI 文案（aapt2 dump strings 提取出来的 1285 条中文串）：
//   自动跳过 → 「添加自动跳过的应用」「新应用自动跳过」「查看支持自动跳过的应用」
//   弹框     → 「取消弹框列表」「启用跳过「应用升级」」「启用跳过「青少年模式」」
//   文件替换 → 「一键替换」「批量替换广告文件」「自动检测并替换」「替换剪贴板」
//   八戒步骤 → 「八戒步骤」「任务描述」「修改参数」
//   悟空实验室 → 「悟空实验室」「应用教程」
// ============================================================================

// ---------------------------------------------------------------- 自动跳过

/**
 * 自动跳过：不写规则也能点掉开屏广告。
 *
 * 名单 = 订阅下发的 3000+ 应用 ∪ 自己加的 − 明确排除的。
 * 原版只能看不能改（白名单在服务端），这里做成可维护的。
 */
@Composable
fun SkipListScreen(ctx: Context) {
    var query by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    val rev = UiState.revision
    val all = remember(rev) { UiState.skipScope.toList().sorted() }

    // 应用名是异步查的（PackageManager 不能在主线程猛调），先把前 200 个的名字补齐
    LaunchedEffect(all) { withContext(Dispatchers.IO) { UiState.ensureLabels(ctx, all.take(200)) } }

    Kit.DetailPage(
        title = "自动跳过",
        onBack = { Navigator.back() },
        actions = {
            TextButton(text = "添加", onClick = { showPicker = true })
        }
    ) {
        item { Kit.Note("进入应用后自动找「跳过 / 跳过广告 / 关闭广告」这类按钮并点掉。只对名单里的应用生效。") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "开启自动跳过",
                    summary = "依赖无障碍服务（八戒）",
                    icon = MiuixIcons.Blocklist,
                    checked = UiState.autoSkipOn(),
                    onChecked = { UiState.setAutoSkip(it) }
                )
                Kit.SwitchRow(
                    title = "对所有应用生效",
                    summary = if (UiState.autoSkipAll()) "关掉则只对名单内的应用生效" else "当前：仅名单内的应用",
                    icon = MiuixIcons.Blocklist,
                    enabled = UiState.autoSkipOn(),
                    checked = UiState.autoSkipAll(),
                    onChecked = { UiState.setAutoSkipAll(it) }
                )
                Kit.SwitchRow(
                    title = "新应用自动跳过",
                    summary = "新装的应用第一次打开即纳入名单",
                    icon = MiuixIcons.Blocklist,
                    enabled = UiState.autoSkipOn(),
                    checked = UiState.newAppSkip(),
                    onChecked = { UiState.setNewAppSkip(it) }
                )
            }
        }

        item { Kit.Section("白名单", "${all.size} 个应用") }
        item {
            Kit.Group {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索包名或应用名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "订阅下发 ${UiState.autoSkip.size} 个 · 自己添加 ${UiState.mySkips.size} 个" +
                        (if (UiState.skipBlock.isNotEmpty()) " · 已排除 ${UiState.skipBlock.size} 个" else ""),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        val shown = all.filter {
            it.contains(query, true) || UiState.labelOf(it).contains(query, true)
        }
        // 白名单可能有几千条，先只渲染前 200 条，靠搜索定位
        val page = shown.take(200)
        items(page, key = { it }) { pkg ->
            Kit.Item(
                title = UiState.labelOf(pkg),
                summary = pkg + when {
                    pkg in UiState.skipBlock -> "（已排除）"
                    pkg in UiState.mySkips -> "（自己添加）"
                    else -> ""
                },
                onClick = { Navigator.go(Dest.AppDetail(pkg)) },
                end = {
                    TextButton(
                        text = if (pkg in UiState.skipBlock) "恢复" else "移除",
                        onClick = {
                            if (pkg in UiState.skipBlock) UiState.addSkip(pkg) else UiState.removeSkip(pkg)
                        }
                    )
                }
            )
        }

        if (shown.size > page.size) {
            item { Kit.Note("还有 ${shown.size - page.size} 个没显示，用上面的搜索框定位。") }
        }
        if (all.isEmpty()) {
            item { Kit.Empty(text = "名单是空的。同步一次规则会带下 3000+ 个应用，也可以点右上角手动加。") }
        }
    }

    if (showPicker) {
        InstalledAppPicker(
            onDismiss = { showPicker = false },
            onPick = { pkg ->
                UiState.addSkip(pkg)
                Toast.makeText(ctx, "已加入自动跳过：$pkg", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ---------------------------------------------------------------- 弹框拦截

/** 弹框拦截：运营弹窗 / 应用升级 / 青少年模式 */
@Composable
fun PopupScreen(ctx: Context) {
    var words by remember { mutableStateOf(UiState.popWords.joinToString(" ")) }
    var dirty by remember { mutableStateOf(false) }
    val rev = UiState.revision

    Kit.DetailPage(title = "弹框拦截", onBack = { Navigator.back() }) {
        item { Kit.Note("点掉「稍后再说 / 我知道了」这类按钮。只认你写在下面的词，不会碰业务弹窗。") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "常见弹框自动关闭",
                    summary = "一些常见弹框广告自动关闭，不包括业务运营弹框",
                    icon = MiuixIcons.Blocklist,
                    checked = UiState.popupOn(),
                    onChecked = { UiState.setPopup(it) }
                )
                Kit.SwitchRow(
                    title = "跳过「应用升级」",
                    summary = "自动点掉常见应用升级弹框",
                    icon = MiuixIcons.Blocklist,
                    enabled = UiState.popupOn(),
                    checked = UiState.popupUpgrade(),
                    onChecked = { UiState.setPopupUpgrade(it) }
                )
                Kit.SwitchRow(
                    title = "跳过「青少年模式」",
                    summary = "自动点掉常见青少年模式弹框",
                    icon = MiuixIcons.Blocklist,
                    enabled = UiState.popupOn(),
                    checked = UiState.popupTeen(),
                    onChecked = { UiState.setPopupTeen(it) }
                )
            }
        }

        item { Kit.Section("取消弹框列表", "空格分隔") }
        item {
            Kit.Group {
                Text(
                    text = "填「要点的那个按钮」的文案。匹配规则：长度 2~10 字、整句相等，" +
                        "或 3 字以上的词按后缀匹配。同一个弹框 8 秒内只点一次。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                TextField(
                    value = words,
                    onValueChange = { words = it; dirty = true },
                    label = "关键词",
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(96.dp),
                    minLines = 3
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = {
                        UiState.setPopWords(words)
                        dirty = false
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                    }, enabled = dirty) { Text(text = "保存") }
                    TextButton(
                        text = "恢复默认",
                        onClick = { words = UiState.DEFAULT_POP_WORDS; dirty = true },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        item { Kit.Section("两个专项开关自带的词") }
        item {
            Kit.Group {
                Kit.KeyValue("应用升级", UiState.UPGRADE_WORDS)
                Kit.KeyValue("青少年模式", UiState.TEEN_WORDS)
                Text(
                    text = "这两个开关打开后，上面的词会自动并进匹配词表，不用重复填。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item { Kit.Section("当前生效", "${com.adhammer.float.PopGuard.words().size} 个词") }
        item {
            Kit.Group {
                Text(
                    text = remember(rev, UiState.popupOn()) {
                        com.adhammer.float.PopGuard.words().joinToString("  ")
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 文件替换

/**
 * 悟空文件替换：把广告缓存文件换成空文件夹 / 空文件 / 直接删掉。
 *
 * 原版有「一键替换 / 批量替换 / 自动检测并替换 / 替换剪贴板」，
 * 这里保留了同样的操作模型：先有 rp=20 的规则，再批量执行。
 */
@Composable
fun ReplaceScreen(ctx: Context) {
    val rev = UiState.revision
    val entries = remember(rev) { UiState.replaceRules() }
    var onlyEnabled by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Kit.DetailPage(title = "文件替换", onBack = { Navigator.back() }) {
        item { Kit.Note("⚠️ 替换有危险：只在你确认过的路径上操作。根目录和 /data 根会被自动拒绝。") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "启用文件替换",
                    summary = "关掉后所有 rp=20 规则都不执行",
                    icon = MiuixIcons.Blocklist,
                    checked = UiState.replaceOn(),
                    onChecked = { UiState.setReplace(it) }
                )
                Kit.SwitchRow(
                    title = "自动检测并替换",
                    summary = "如果检查到广告文件被应用恢复，将自动完成替换（依赖辅助服务）",
                    icon = MiuixIcons.Blocklist,
                    enabled = UiState.replaceOn(),
                    checked = UiState.replaceAuto(),
                    onChecked = { UiState.setReplaceAuto(it) }
                )
            }
        }

        item { Kit.Section("批量操作", "${entries.size} 条规则") }
        item {
            Kit.Group {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val n = entries.size
                            msg = "已排队 $n 条：目标应用下次启动时执行替换（需要强制停止后重开）"
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(text = "一键替换") }
                    TextButton(
                        text = "全部启用",
                        onClick = { entries.forEach { UiState.toggleRule(it.pkg, RuleTypes.WU_KONG_REPLACE, it.index, true) } },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    TextButton(
                        text = "全部禁用",
                        onClick = { entries.forEach { UiState.toggleRule(it.pkg, RuleTypes.WU_KONG_REPLACE, it.index, false) } },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Switch(checked = onlyEnabled, onCheckedChange = { onlyEnabled = it })
                    Text(
                        text = "只显示启用中的",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    text = "从剪贴板导入：把路径列表复制好，回到某条规则的编辑页粘贴即可；" +
                        "也可以直接去「应用 → 某个 App → 文件」里加。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (msg.isNotBlank()) item { Kit.Note(msg) }

        val shown = entries.filter { !onlyEnabled || it.item.isEnable }
        if (shown.isEmpty()) {
            item { Kit.Empty(text = "还没有文件替换规则。去「应用 → 某个 App → 悟空 · 文件」里加。") }
        } else {
            shown.groupBy { it.pkg }.forEach { (pkg, list) ->
                item { Kit.Section(UiState.labelOf(pkg), "${list.size} 条") }
                items(list) { e ->
                    Kit.Item(
                        title = e.item.adKey?.takeIf { it.isNotBlank() } ?: e.item.ad ?: "（空路径）",
                        summary = "${Actions.describe(e.item.actionType)} · ${e.item.displayRemark.ifBlank { "无备注" }}",
                        onClick = { Navigator.go(Dest.RuleEdit(pkg, RuleTypes.WU_KONG_REPLACE, e.index)) },
                        end = {
                            Switch(
                                checked = e.item.isEnable,
                                onCheckedChange = {
                                    UiState.toggleRule(pkg, RuleTypes.WU_KONG_REPLACE, e.index, it)
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 沙僧代理

/**
 * 沙僧代理：VpnService 版域名拦截。
 *
 * 和「净化行为 → 域名拦截」不是一回事：那个是 hook 版（只在被 hook 的进程里生效），
 * 这个对所有应用生效，也不需要 Xposed。
 */
@Composable
fun SengVpnScreen(ctx: Context) {
    var on by remember { mutableStateOf(com.adhammer.vpn.SengVpnService.running) }
    var showPicker by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val rev = UiState.revision
    val exclude = remember(rev) { UiState.vpnExclude().toList().sorted() }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            com.adhammer.vpn.SengVpnService.start(ctx)
            on = true
            msg = "已开启沙僧代理"
        } else {
            msg = "需要授权 VPN 才能开启"
        }
    }

    Kit.DetailPage(title = "沙僧代理", onBack = { Navigator.back() }) {
        item { Kit.Note("通过系统 VPN 把 DNS 接管过来，命中广告域名直接回 0.0.0.0。不解密 HTTPS、不要证书。") }

        item {
            Kit.RowGroup {
                Kit.SwitchRow(
                    title = "开启代理服务",
                    summary = if (on) "代理服务运行中…" else "开启后通知栏会有一条常驻通知",
                    icon = MiuixIcons.Blocklist,
                    checked = on,
                    onChecked = { want ->
                        if (want) {
                            val intent = com.adhammer.vpn.SengVpnService.prepare(ctx)
                            if (intent != null) launcher.launch(intent)
                            else {
                                com.adhammer.vpn.SengVpnService.start(ctx)
                                on = true
                                msg = "已开启沙僧代理"
                            }
                        } else {
                            com.adhammer.vpn.SengVpnService.stop(ctx)
                            on = false
                            msg = "已关闭"
                        }
                    }
                )
            }
        }

        if (msg.isNotBlank()) item { Kit.Note(msg) }

        item { Kit.Section("拦截规模") }
        item {
            Kit.Group {
                Kit.KeyValue("全局域名", "${UiState.hosts.size}")
                Kit.KeyValue("按应用域名", "${UiState.appHosts.values.sumOf { it.size }}")
                Kit.KeyValue("合计", "${UiState.hosts.size + UiState.appHosts.values.sumOf { it.size }}")
                Text(
                    text = "改完域名要重启代理才生效：先关再开。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            Kit.Section("不走代理的应用", "${exclude.size} 个") {
                TextButton(text = "添加", onClick = { showPicker = true })
            }
        }
        if (exclude.isEmpty()) {
            item { Kit.Note("默认只排除本应用。银行 / 支付类应用如果出问题，可以单独排除。") }
        } else {
            items(exclude) { pkg ->
                Kit.Item(
                    title = UiState.labelOf(pkg),
                    summary = pkg,
                    end = {
                        TextButton(
                            text = "移除",
                            onClick = {
                                UiState.setVpnExclude(UiState.vpnExclude() - pkg)
                            }
                        )
                    }
                )
            }
        }

        item { Kit.Section("说明") }
        item {
            Kit.Group {
                Text(
                    text = "DNS 级拦截只能按域名拦，不能按 URL 路径拦 —— URL 级请交给悟空的 rp=11（URL 拦截）。\n\n" +
                        "原版是「VPN + 中间人 + 自签名证书」，能拦 URL 但要装证书、且会解密 HTTPS；" +
                        "这一版刻意不做中间人：隐私风险更小，也不需要装证书。\n\n" +
                        "hook 版沙僧（净化行为 → 域名拦截）和这个可以同时开着，规则是同一份。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }

    if (showPicker) {
        InstalledAppPicker(onDismiss = { showPicker = false }, onPick = { pkg ->
            UiState.setVpnExclude(UiState.vpnExclude() + pkg)
        })
    }
}

// ---------------------------------------------------------------- 悟空实验室

/**
 * 悟空实验室：规则自检。
 *
 * 给极客用的：填一个 Activity（或 View id / URL），看它会命中哪些规则、
 * 命中后执行什么动作、这条规则是哪个订阅包下发的。不用真的打开 App 试。
 */
@Composable
fun LabScreen(ctx: Context) {
    var pkg by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<LabHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    Kit.DetailPage(title = "悟空实验室", onBack = { Navigator.back() }) {
        item { Kit.Note("输入包名 + 主键（Activity 全名 / View id / 域名），看它会命中哪些规则。") }

        item {
            Kit.Group {
                TextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = "包名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    text = "选择已安装应用",
                    onClick = { showPicker = true },
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = "主键（可留空，只列该包全部规则）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = {
                        result = probe(pkg.trim(), key.trim())
                        searched = true
                    }) { Text(text = "检测") }
                    if (UiState.appIndex.isNotEmpty()) {
                        TextButton(
                            text = "用最近适配的应用",
                            onClick = { pkg = UiState.appIndex.first().first },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        if (searched) {
            item { Kit.Section("命中结果", "${result.size} 条") }
            if (result.isEmpty()) {
                item { Kit.Empty(text = "没命中任何规则。换个主键试试，或去 APK 分析器扫一遍这个应用。") }
            } else {
                items(result) { h ->
                    Kit.Item(
                        title = h.title,
                        summary = h.detail,
                        onClick = { Navigator.go(Dest.AppDetail(h.pkg)) }
                    )
                }
            }
        }

        item { Kit.Section("应用教程") }
        item {
            Kit.Group {
                LabDoc("Activity", "开屏广告多数是一个独立 Activity。用「关闭活动页(at=1)」最稳；「禁用(at=2)」可能引起闪退。")
                LabDoc("URL", "rp=11，ad 填 xxx.Url，adKey 填域名。适合拦广告接口。")
                LabDoc("View", "rp=14，ad 是所在 Activity（填 * 表示所有页面），adKey 是 View 匹配数组的 JSON。")
                LabDoc("组件", "rp=15，at=500~503 屏蔽四大组件；at=9 在 startActivity 阶段掏空 Intent，效果最好。")
                LabDoc("文件", "rp=20，ad 是文件路径，at=1 换成空文件夹 / at=2 换成空文件 / at=3 删除。")
                LabDoc("八戒", "rp=1111，无障碍按步骤模拟点击，适合没有稳定 id 的按钮。")
            }
        }
    }

    if (showPicker) {
        InstalledAppPicker(onDismiss = { showPicker = false }, onPick = { pkg = it; showPicker = false })
    }
}

private data class LabHit(val pkg: String, val title: String, val detail: String)

/** 本地检索：不做 hook，只是把规则按主键过滤一遍 */
private fun probe(pkg: String, key: String): List<LabHit> {
    if (pkg.isBlank()) return emptyList()
    val out = ArrayList<LabHit>()
    val k = key.trim()
    UiState.packsOf(pkg).forEach { pack ->
        pack.rules.forEach { r ->
            val ad = r.ad ?: ""
            val adKey = r.adKey ?: ""
            val matched = k.isBlank() ||
                ad.equals(k, true) || ad.endsWith(k, true) ||
                adKey.contains(k, true)
            if (!matched) return@forEach
            out.add(
                LabHit(
                    pkg = pkg,
                    title = "${UiState.typeName(r.ruleType ?: pack.ruleType)} · ${Actions.describe(r.actionType)}",
                    detail = buildString {
                        append(ad.ifBlank { "（无主键）" })
                        if (adKey.isNotBlank()) append(" → ${adKey.take(80)}")
                        append("\n来源：${pack.name ?: "未命名"}")
                        append(if (r.isEnable) " · 启用中" else " · 已停用")
                        if (r.delay > 0) append(" · 延迟 ${r.delay}ms")
                    }
                )
            )
        }
    }
    // 八戒任务也一起列出来
    UiState.tasksOf(pkg).forEach { t ->
        if (k.isBlank() || (t.act ?: "").contains(k, true) || (t.name ?: "").contains(k, true)) {
            out.add(
                LabHit(
                    pkg = pkg,
                    title = "八戒任务 · ${t.name ?: t.act ?: "未命名"}",
                    detail = "${t.act ?: "任意页面"} · ${t.steps.size} 步" +
                        (if (t.isEnable) " · 启用中" else " · 已停用")
                )
            )
        }
    }
    return out
}

@Composable
private fun LabDoc(title: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------- 八戒步骤编辑

/**
 * 八戒任务编辑器：任务元信息 + 步骤列表（增删改、上下移动）。
 *
 * 原版「八戒步骤 / 任务描述 / 修改参数」。
 * index = -1 表示新建。
 */
@Composable
fun TaskEditScreen(ctx: Context, pkg: String, index: Int) {
    val rev = UiState.revision
    val src = remember(rev) { UiState.tasksOf(pkg).getOrNull(index) }
    var name by remember { mutableStateOf(src?.name ?: "") }
    var act by remember { mutableStateOf(src?.act ?: "") }
    var enable by remember { mutableStateOf(src?.isEnable ?: true) }
    var remark by remember { mutableStateOf(src?.remark ?: "") }
    var steps by remember { mutableStateOf(src?.steps?.toMutableList() ?: mutableListOf()) }
    var editStep by remember { mutableStateOf<Int?>(null) }

    Kit.DetailPage(
        title = if (index < 0) "新建任务" else "编辑任务",
        subtitle = pkg,
        onBack = { Navigator.back() },
        actions = {
            TextButton(text = "保存", onClick = {
                val task = Task(
                    act = act.trim().ifBlank { null },
                    isEnable = enable,
                    name = name.trim().ifBlank { null },
                    pkg = pkg,
                    remark = remark.trim().ifBlank { null },
                    ruleType = RuleTypes.BA_JIE_TASK,
                    steps = steps.toList()
                )
                UiState.saveTask(pkg, index, task)
                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                Navigator.back()
            })
        }
    ) {
        item { Kit.Section("任务描述") }
        item {
            Kit.Group {
                TextField(value = name, onValueChange = { name = it }, label = "任务名", singleLine = true, modifier = Modifier.fillMaxWidth())
                TextField(
                    value = act,
                    onValueChange = { act = it },
                    label = "目标 Activity（留空 = 所有页面）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                TextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = "备注",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Switch(checked = enable, onCheckedChange = { enable = it })
                    Text(
                        text = "启用这个任务",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        item {
            Kit.Section("八戒步骤", "${steps.size} 步") {
                TextButton(text = "添加步骤", onClick = {
                    steps.add(Step(at = 8))
                    editStep = steps.lastIndex
                })
            }
        }

        if (steps.isEmpty()) {
            item {
                Kit.Empty(
                    text = "还没有步骤。步骤按顺序执行：先定位控件再点击，也可以直接点坐标或滑动。",
                    actionText = "加一个点击步骤",
                    onAction = { steps.add(Step(at = 8)); editStep = 0 }
                )
            }
        } else {
            items(steps.size) { i ->
                val s = steps[i]
                Kit.Item(
                    title = "第 ${i + 1} 步 · ${stepLabel(s)}",
                    summary = stepSummary(s),
                    onClick = { editStep = i },
                    end = {
                        TextButton(text = "↑", onClick = {
                            if (i > 0) {
                                val t = steps[i]; steps[i] = steps[i - 1]; steps[i - 1] = t
                            }
                        })
                        TextButton(text = "↓", onClick = {
                            if (i < steps.lastIndex) {
                                val t = steps[i]; steps[i] = steps[i + 1]; steps[i + 1] = t
                            }
                        })
                        TextButton(text = "删", onClick = { steps.removeAt(i) })
                    }
                )
            }
        }

        item { Kit.Note("改完要点右上角「保存」。八戒跑在辅助服务里，保存后下次进这个页面就生效。") }
    }

    val pos = editStep
    if (pos != null && pos in steps.indices) {
        StepDialog(
            step = steps[pos],
            onDismiss = { editStep = null },
            onConfirm = { newStep ->
                steps[pos] = newStep
                editStep = null
            }
        )
    }
}

private fun stepLabel(s: Step): String = when {
    s.x >= 0 && s.y >= 0 -> "坐标点击"
    s.sx0 >= 0 && s.sy0 >= 0 -> "滑动"
    !s.t.isNullOrBlank() -> "点文本"
    !s.vId.isNullOrBlank() -> "点 id"
    !s.wv.isNullOrBlank() -> "点控件"
    else -> "未设置"
}

private fun stepSummary(s: Step): String = buildString {
    if (!s.t.isNullOrBlank()) append("文本：${s.t}\n")
    if (!s.vId.isNullOrBlank()) append("id：${s.vId}\n")
    if (!s.wv.isNullOrBlank()) append("控件：${s.wv}\n")
    if (s.x >= 0 && s.y >= 0) append("坐标：${s.x},${s.y}\n")
    if (s.sx0 >= 0) append("滑动：(${s.sx0},${s.sy0}) → (${s.sx1},${s.sy1})\n")
    if (s.d > 0) append("延迟 ${s.d}ms · ")
    append("重复 ${s.c.coerceAtLeast(1)} 次")
    if (s.prs) append(" · 长按")
}.trim()

/** 单个步骤的参数编辑 */
@Composable
private fun StepDialog(step: Step, onDismiss: () -> Unit, onConfirm: (Step) -> Unit) {
    var t by remember { mutableStateOf(step.t ?: "") }
    var vId by remember { mutableStateOf(step.vId ?: "") }
    var wv by remember { mutableStateOf(step.wv ?: "") }
    var x by remember { mutableStateOf(if (step.x >= 0) step.x.toString() else "") }
    var y by remember { mutableStateOf(if (step.y >= 0) step.y.toString() else "") }
    var d by remember { mutableStateOf(step.d.toString()) }
    var c by remember { mutableStateOf(step.c.toString()) }
    var sw by remember { mutableStateOf(false) }
    var sx0 by remember { mutableStateOf(if (step.sx0 >= 0) step.sx0.toString() else "") }
    var sy0 by remember { mutableStateOf(if (step.sy0 >= 0) step.sy0.toString() else "") }
    var sx1 by remember { mutableStateOf(if (step.sx1 >= 0) step.sx1.toString() else "") }
    var sy1 by remember { mutableStateOf(if (step.sy1 >= 0) step.sy1.toString() else "") }

    OverlayDialog(
        show = true,
        title = "修改参数",
        onDismissRequest = onDismiss
    ) {
        Column {
            Text(
                text = "按 文本 / id / 控件类名 定位，三者都留空时改用坐标或滑动。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            TextField(value = t, onValueChange = { t = it }, label = "目标文本", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TextField(value = vId, onValueChange = { vId = it }, label = "目标 view id", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TextField(value = wv, onValueChange = { wv = it }, label = "控件类名", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = sw, onCheckedChange = { sw = it })
                Text(text = "用坐标 / 滑动", fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
            if (sw) {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextField(value = x, onValueChange = { x = it }, label = "X", singleLine = true, modifier = Modifier.weight(1f))
                    TextField(value = y, onValueChange = { y = it }, label = "Y", singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextField(value = sx0, onValueChange = { sx0 = it }, label = "起点X", singleLine = true, modifier = Modifier.weight(1f))
                    TextField(value = sy0, onValueChange = { sy0 = it }, label = "起点Y", singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextField(value = sx1, onValueChange = { sx1 = it }, label = "终点X", singleLine = true, modifier = Modifier.weight(1f))
                    TextField(value = sy1, onValueChange = { sy1 = it }, label = "终点Y", singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextField(value = d, onValueChange = { d = it }, label = "延迟 ms", singleLine = true, modifier = Modifier.weight(1f))
                TextField(value = c, onValueChange = { c = it }, label = "重复次数", singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onConfirm(
                        step.copy(
                            t = t.trim().ifBlank { null },
                            vId = vId.trim().ifBlank { null },
                            wv = wv.trim().ifBlank { null },
                            x = x.trim().toIntOrNull() ?: -1,
                            y = y.trim().toIntOrNull() ?: -1,
                            sx0 = sx0.trim().toIntOrNull() ?: -1,
                            sy0 = sy0.trim().toIntOrNull() ?: -1,
                            sx1 = sx1.trim().toIntOrNull() ?: -1,
                            sy1 = sy1.trim().toIntOrNull() ?: -1,
                            d = d.trim().toLongOrNull() ?: 0L,
                            c = c.trim().toIntOrNull() ?: 1
                        )
                    )
                }, modifier = Modifier.weight(1f)) { Text(text = "确定") }
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- 公共：已安装应用选择器

@Composable
fun InstalledAppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val list = runCatching {
                pm.getInstalledPackages(0).mapNotNull { pi ->
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

    OverlayDialog(show = true, title = "选择应用", onDismissRequest = onDismiss) {
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
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.height(360.dp).padding(top = 8.dp)
            ) {
                items(shown) { (p, label) ->
                    Kit.Item(title = label, summary = p, onClick = { onPick(p) })
                }
            }
        }
    }
}
