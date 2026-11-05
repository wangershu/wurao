package com.wurao.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wurao.assist.SnapshotService
import com.wurao.core.Cfg
import com.wurao.core.RiskGate
import com.wurao.core.analyze.AdComponentFinder
import com.wurao.core.analyze.RuleGen
import com.wurao.core.model.Act
import com.wurao.core.model.Origin
import com.wurao.core.model.Rule
import com.wurao.core.store.RuleStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 应用页筛选档（评审拍板：按钮组三档） */
private enum class AppsFilter(val label: String) { ALL("全部"), HIT("只看拦过的"), SCOPE("已在 LSPosed 勾选") }

/**
 * 应用列表：搜应用 + 三档筛选 + 按命中数排序。
 *
 * 只列有启动入口的应用（第三方为主），避免几千条系统组件。
 */
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppsFilter.ALL) }

    LaunchedEffect(Unit) {
        apps = runCatching {
            val pm = ctx.packageManager
            val main = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = pm.queryIntentActivities(main, 0)
            resolved.map {
                it.activityInfo.packageName to it.loadLabel(pm).toString()
            }.distinctBy { it.first }.sortedBy { it.second }
        }.getOrDefault(emptyList())
        loaded = true
    }

    // LSPosed 作用域（框架服务连着才拿得到）
    val scope = remember { CfgPush.scope().toSet() }

    Kit.Page(
        title = "应用",
        subtitle = if (loaded) "共 ${apps.size} 个应用" else "加载中…",
    ) {
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜应用（名字或包名）",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------- 筛选按钮组 ----------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppsFilter.entries.forEach { f ->
                    val sel = filter == f
                    val enabled = f != AppsFilter.SCOPE || CfgPush.bound
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (sel) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.surface
                            )
                            .clickable(enabled = enabled) { filter = f }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            f.label,
                            fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                            color = when {
                                !enabled -> MiuixTheme.colorScheme.disabledOnSurface
                                sel -> androidx.compose.ui.graphics.Color.White
                                else -> MiuixTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        if (filter == AppsFilter.SCOPE && !CfgPush.bound) {
            item { Kit.Note("框架未连接，拿不到 LSPosed 的作用域名单。去 LSPosed 里启用「无扰」并重开应用。") }
        }

        if (loaded && apps.size <= 1) {
            item {
                Kit.Note(
                    "只拿到 ${apps.size} 个应用 —— 缺 MIUI 的 GET_INSTALLED_APPS 运行时权限。" +
                        "也可以用下面的「手动输入包名」直接进详情页。"
                )
            }
        }

        // 按命中数排序，每行告诉你这个应用拦了多少。
        // 注意：这里是 LazyListScope 的 lambda，不能用 remember（不是 @Composable 上下文）；
        // 好在这只是普通计算，命中记录就那么几条，每次重算也不贵
        val hitCount = HitStore.all().groupBy { it.pkg }.mapValues { e -> e.value.sumOf { h -> h.count } }
        val appsSorted = apps
            .filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
            .filter { filter != AppsFilter.HIT || (hitCount[it.first] ?: 0) > 0 }
            .filter { filter != AppsFilter.SCOPE || it.first in scope }
            .sortedByDescending { hitCount[it.first] ?: 0 }

        items(appsSorted, key = { it.first }) { (pkg, label) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
                pressFeedbackType = PressFeedbackType.Tilt,
                onClick = { Navigator.go(Dest.AppDetail(pkg)) },
            ) {
                Kit.PlainRow(
                    title = label,
                    summary = if ((hitCount[pkg] ?: 0) > 0) "$pkg · 拦了 ${hitCount[pkg]} 次" else pkg,
                    start = { Kit.RowStart { AppIcon(pkg) } },
                    onClick = { Navigator.go(Dest.AppDetail(pkg)) },
                )
            }
        }

        // ---------- 手动输入包名 ----------
        item {
            var text by remember { mutableStateOf("") }
            Kit.Group {
                Text(
                    "手动输入包名",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "包名",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = "打开",
                        enabled = text.isNotBlank(),
                        onClick = { Navigator.go(Dest.AppDetail(text.trim())) },
                    )
                }
            }
        }
    }
}

/**
 * 应用图标。取不到就退回一个通用图标 —— 列表中一排灰方块很难看。
 * 必须固定小尺寸：应用图标 intrinsic 常常是 512x512，
 * 按原尺寸建 Bitmap 一个就 1MB，几百个应用直接把内存吃满（实测堆到 240MB/256MB）。
 */
@Composable
fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val px = 64
            val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(android.graphics.Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bmp,
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant),
        )
    }
}

/**
 * 应用详情：命中的规则 + 风险级开关 + 抓屏 + 自动分析。
 *
 * 业务逻辑与旧版一致（riskKey / componentsKey / 抓屏标记 / 候选勾选），只换了行样式。
 */
@Composable
fun AppDetailScreen(pkg: String) {
    val ctx = LocalContext.current
    var kinds by remember { mutableStateOf(Cfg.decodeKinds(Ui.prefs(ctx).getString(Cfg.riskKey(pkg), null))) }

    val label = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }
    // 这个应用的规则：自抓的逐条可管，订阅的只报条数（几万条不铺开）
    val appRules = run { Ui.ruleRev; RuleStore.all().filter { ruleScopePkg(it) == pkg } }
    val myRules = appRules.filter { it.origin is Origin.User }
    val subCount = appRules.size - myRules.size
    var editNote by remember { mutableStateOf<Rule?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<Rule?>(null) }

    fun saveKinds(next: Set<RiskGate.Kind>) {
        kinds = next
        Ui.prefs(ctx).edit().putString(Cfg.riskKey(pkg), Cfg.encodeKinds(next)).apply()
        CfgPush.flush(ctx)
    }

    Kit.DetailPage(title = label, subtitle = pkg, onBack = { Navigator.back() }) {
        // ---------- 实体头 ----------
        item {
            Kit.RowGroup {
                Kit.Header(title = label, subtitle = pkg, start = { AppIcon(pkg) })
            }
        }

        // ---------- 这个应用的规则（删除/编辑会重新落位到 hook） ----------
        item { Kit.Section("这个应用的规则", "${myRules.size} 条自抓 · 订阅 $subCount 条") }
        item { Kit.Note("开关控制规则是否生效；长按某条规则可以改备注或删除。") }
        if (myRules.isEmpty()) {
            item { Kit.Empty("这个应用还没有自己抓的规则。切过去点悬浮球抓一屏，选中的控件会存到这里。") }
        } else {
            items(myRules, key = { "rule:" + it.id }) { r ->
                RuleRow(
                    r = r,
                    onEditNote = { editNote = r; noteDraft = r.note },
                    onDelete = { confirmDel = r },
                    onToggle = {
                        RuleStore.setEnabled(r.id, !r.enabled)
                        Ui.pushRulesAsync(ctx)
                        Ui.ruleRev++
                    },
                )
            }
        }

        // ---------- 风险级 ----------
        item { Kit.Section("风险级", "默认全关") }
        item { Kit.Note("开了之后这个 App 若出现异常，先点「全部退回」。") }
        item {
            Kit.RowGroup {
                RiskGate.Kind.entries.forEach { kind ->
                    Kit.SwitchRow(
                        title = kind.label,
                        summary = riskHint(kind),
                        checked = kinds.contains(kind),
                        onChecked = { on ->
                            saveKinds(if (on) kinds + kind else kinds - kind)
                        },
                    )
                }
            }
        }
        item {
            Kit.RowGroup {
                Kit.PlainRow(
                    title = "全部退回",
                    summary = "清掉这个应用的所有风险级开关",
                    onClick = { saveKinds(emptySet()) },
                )
            }
        }

        // ---------- 自动分析（子页面） ----------
        item {
            Kit.ArrowItem(
                title = "自动分析",
                summary = "本地扫组件挖广告候选，进子页面看结果",
                onClick = { Navigator.go(Dest.AppAnalysis(pkg)) },
            )
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
            confirmDel = null
            Ui.pushRulesAsync(ctx)
            Ui.ruleRev++
        },
    )
    NoteEditDialog(
        rule = editNote,
        draft = noteDraft,
        onDraft = { noteDraft = it },
        onDismiss = { editNote = null },
        onSave = { r, text ->
            RuleStore.setNote(r.id, text)
            editNote = null
            Ui.pushRulesAsync(ctx)
            Ui.ruleRev++
        },
    )
}

private fun riskHint(kind: RiskGate.Kind): String = when (kind) {
    RiskGate.Kind.COMPONENT -> "广告页还没打开就拦掉，不闪不崩"
    RiskGate.Kind.NET -> "广告域名解析到本地，请求不出门"
    RiskGate.Kind.SDK -> "让广告 SDK 起不来（最抗改版，但可能连累 App）"
    RiskGate.Kind.SENSOR -> "摇一摇跳不了。注意：计步、摇一摇换歌、重力操控也靠它"
}
