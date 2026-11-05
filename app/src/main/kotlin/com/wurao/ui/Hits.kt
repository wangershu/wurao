package com.wurao.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wurao.core.store.RuleStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 命中记录（主页「今日拦截」的二级页）。
 *
 * 这里是排错主入口：每一行都是"某条规则真的动过手"，所以每行直接给停用按钮。
 * 停用改规则本身（不是另存一份名单）—— 规则库只有一处，不会两边对不上；
 * 停用不等于删除：规则还在库里，随时能恢复。
 */
@Composable
fun HitsScreen() {
    val ctx = LocalContext.current
    // hitRev / ruleRev 变化（停用/恢复/刷新）→ 重读
    val rows = run {
        Ui.hitRev; Ui.ruleRev
        HitStore.all()
    }

    Kit.DetailPage(title = "今日拦截", onBack = { Navigator.back() }) {
        item { Kit.Note("同一条规则反复命中只占一行（只涨次数），所以开一天 App 也不会刷屏。") }
        item { Kit.Note("右边的开关停用的是规则本身，应用详情页里的规则开关会同步变化。") }
        if (rows.isEmpty()) {
            item { Kit.Empty("还没有记录。目标 App 里真的动手之后，记录会从它的进程送回来。", "刷新") { Ui.hitRev++ } }
        } else {
            items(rows, key = { it.pkg + "|" + it.ruleId }) { r ->
                // 停用状态以规则库为准（与应用详情页同一个口径）
                val off = RuleStore.all().firstOrNull { it.id == r.ruleId }?.enabled == false
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.describe(), fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text(
                                r.pkg + " · " + r.target,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                "${r.count} 次 · ${timeAgo(r.lastAtMs)}" + if (off) " · 已停用" else "",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = !off,
                            onCheckedChange = {
                                // 映射到规则库：与应用详情页同一个状态源
                                RuleStore.setEnabled(r.ruleId, off)
                                Ui.pushRulesAsync(ctx)
                                Ui.hitRev++
                                Ui.ruleRev++
                            },
                        )
                    }
                }
            }
        }
    }
}
