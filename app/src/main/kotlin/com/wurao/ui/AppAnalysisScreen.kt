package com.wurao.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wurao.core.Cfg
import com.wurao.core.analyze.AdComponentFinder
import top.yukonga.miuix.kmp.basic.Switch

/**
 * 自动分析子页面：本地扫组件挖广告候选。
 * 从应用详情页拆出来，避免详情页底下挂一大片结果。
 */
@Composable
fun AppAnalysisScreen(pkg: String) {
    val ctx = LocalContext.current
    var candidates by remember { mutableStateOf<List<AdComponentFinder.Candidate>>(emptyList()) }
    var picked by remember {
        mutableStateOf(Cfg.decodeList(Ui.prefs(ctx).getString(Cfg.componentsKey(pkg), null)).toSet())
    }
    var note by remember { mutableStateOf("") }
    val label = remember(pkg) {
        runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun savePicked(next: Set<String>) {
        picked = next
        Ui.prefs(ctx).edit().putString(Cfg.componentsKey(pkg), Cfg.encodeList(next)).apply()
        CfgPush.flush(ctx)
    }

    Kit.DetailPage(title = "自动分析", subtitle = label, onBack = { Navigator.back() }) {
        item {
            Kit.Note("本地扫这个 App 的组件挖广告候选，不需要目标进程被 hook。结果仅供参考，勾选后才生效。")
        }
        item {
            Kit.ArrowItem(
                title = "扫一下这个 App",
                summary = "读 activities / services / providers / receivers，按特征打分",
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
                    note = "扫到 ${comps.size} 个组件，候选 ${candidates.size} 条"
                },
            )
        }
        if (candidates.isNotEmpty()) {
            item { Kit.Note(note) }
            item {
                Kit.RowGroup {
                    candidates.forEach { c ->
                        val on = picked.contains(c.name)
                        Kit.PlainRow(
                            title = c.name.substringAfterLast('.'),
                            summary = if (c.level == AdComponentFinder.Level.INFRA)
                                "⚠ 基础设施：${c.reason}" else c.reason,
                            onClick = { savePicked(if (on) picked - c.name else picked + c.name) },
                            end = {
                                Switch(
                                    checked = on,
                                    onCheckedChange = {
                                        savePicked(if (it) picked + c.name else picked - c.name)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        } else if (note.isNotEmpty()) {
            item { Kit.Note(note) }
        }
    }
}
