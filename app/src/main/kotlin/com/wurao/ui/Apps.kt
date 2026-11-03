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
    val hits = run {
        Ui.hitRev
        HitStore.forApp(pkg)
    }
    var kinds by remember { mutableStateOf(Cfg.decodeKinds(Ui.prefs(ctx).getString(Cfg.riskKey(pkg), null))) }
    var candidates by remember { mutableStateOf<List<AdComponentFinder.Candidate>>(emptyList()) }
    var picked by remember { mutableStateOf(Cfg.decodeList(Ui.prefs(ctx).getString(Cfg.componentsKey(pkg), null)).toSet()) }
    var scanNote by remember { mutableStateOf("") }

    val label = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    fun saveKinds(next: Set<RiskGate.Kind>) {
        kinds = next
        Ui.prefs(ctx).edit().putString(Cfg.riskKey(pkg), Cfg.encodeKinds(next)).apply()
        CfgPush.flush(ctx)
    }

    fun savePicked(next: Set<String>) {
        picked = next
        Ui.prefs(ctx).edit().putString(Cfg.componentsKey(pkg), Cfg.encodeList(next)).apply()
        CfgPush.flush(ctx)
    }

    Kit.DetailPage(title = label, subtitle = pkg, onBack = { Navigator.back() }) {
        // ---------- 实体头 ----------
        item {
            Kit.RowGroup {
                Kit.Header(title = label, subtitle = pkg, start = { AppIcon(pkg) })
            }
        }

        // ---------- 命中的规则 ----------
        item { Kit.Section("命中的规则", "${hits.size}") }
        if (hits.isEmpty()) {
            item { Kit.Empty("这个应用里还没动过手。用一阵子，拦过的东西会列在这里，可逐条停用。") }
        } else {
            item {
                Kit.Note(
                    "这个应用拦过 ${hits.sumOf { it.count }} 次，最后一次 ${timeAgo(hits.maxOf { it.lastAtMs })}"
                )
            }
            items(hits, key = { it.ruleId }) { h ->
                val rule = RuleStore.all().firstOrNull { it.id == h.ruleId }
                val off = rule?.enabled == false
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(h.note, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text(
                                "${h.count} 次 · ${h.target}",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        TextButton(
                            text = if (off) "恢复" else "停用",
                            onClick = {
                                // 停用改规则本身（规则库只有一处）；停用不等于删除
                                RuleStore.setEnabled(h.ruleId, off)
                                Ui.pushRulesAsync(ctx)
                                Ui.hitRev++
                            },
                        )
                    }
                }
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
                    summary = "清掉这个应用的所有风险级开关与组件名单",
                    onClick = { saveKinds(emptySet()); savePicked(emptySet()) },
                )
            }
        }

        // ---------- 抓屏 ----------
        item { Kit.Section("还有广告？") }
        item {
            Kit.Note("点下面的按钮，然后切回这个应用 —— 控件会被橙框框出来，点哪个就治哪个（点空白处取消）。")
        }
        item {
            if (!SnapshotService.active) {
                Kit.ArrowItem(
                    title = "抓屏服务没连上",
                    summary = "Xposed 读不到别的 App 的界面，这层必须靠无障碍。若设置里已打开过，请关掉再打开一次 —— 更新 APK 会让服务失效。",
                    onClick = {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            } else {
                Kit.ArrowItem(
                    title = "抓一屏",
                    summary = scanNote.ifBlank { "切到 $label，控件会被橙框框出来，点一下生成规则" },
                    onClick = {
                        SnapshotService.wantPkg = pkg
                        SnapshotService.wantCapture = true
                        scanNote = "已就绪：切到 $label，控件会被橙框框出来，点一下就选中并生成规则"
                    },
                )
            }
        }

        // ---------- 自动分析 ----------
        item { Kit.Section("自动分析") }
        item {
            Kit.ArrowItem(
                title = "扫一下这个 App",
                summary = "本地扫它的组件挖广告候选，不需要目标进程被 hook",
                onClick = {
                    val comps = runCatching {
                        val info = ctx.packageManager.getPackageInfo(
                            pkg,
                            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                                PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
                        )
                        buildList {
                            info.activities?.forEach { add("activity" to it.name) }
                            info.services?.forEach { add("service" to it.name) }
                            info.providers?.forEach { add("provider" to it.name) }
                            info.receivers?.forEach { add("receiver" to it.name) }
                        }
                    }.getOrDefault(emptyList())
                    candidates = AdComponentFinder.find(comps)
                    scanNote = "扫到 ${comps.size} 个组件，候选 ${candidates.size} 条（仅供参考，需要你勾选）"
                },
            )
        }
        if (candidates.isNotEmpty()) {
            item { Kit.Note(scanNote) }
            item {
                Kit.RowGroup {
                    candidates.forEach { c ->
                        val on = picked.contains(c.name)
                        Kit.PlainRow(
                            title = c.name.substringAfterLast('.'),
                            summary = (if (c.level == AdComponentFinder.Level.INFRA) "⚠ 基础设施：${c.reason}" else c.reason),
                            onClick = { savePicked(if (on) picked - c.name else picked + c.name) },
                            end = {
                                top.yukonga.miuix.kmp.basic.Switch(
                                    checked = on,
                                    onCheckedChange = { savePicked(if (it) picked + c.name else picked - c.name) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun riskHint(kind: RiskGate.Kind): String = when (kind) {
    RiskGate.Kind.COMPONENT -> "广告页还没打开就拦掉，不闪不崩"
    RiskGate.Kind.NET -> "广告域名解析到本地，请求不出门"
    RiskGate.Kind.SDK -> "让广告 SDK 起不来（最抗改版，但可能连累 App）"
    RiskGate.Kind.SENSOR -> "摇一摇跳不了。注意：计步、摇一摇换歌、重力操控也靠它"
}
