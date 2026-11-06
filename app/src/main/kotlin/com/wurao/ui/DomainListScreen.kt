package com.wurao.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wurao.core.DomainExclusions
import com.wurao.core.model.Domain
import com.wurao.core.store.RuleStore
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 域名表子页面：把 adclose.txt 的全部域名列出来，可搜索、可单条移除。
 *
 * ADclose 的 block 语义：屏蔽错了能把那一条从黑名单捞出去（写进排除名单），
 * 重新导入订阅也不会把它带回来；「恢复」一次清空排除名单。
 * LazyColumn 按需组合，1.7 万条也不会一次性堆 Card。
 */
@Composable
fun DomainListScreen() {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editRev by remember { mutableStateOf(0) }
    val excluded = DomainExclusions.load()
    val domains = remember(editRev) {
        val fromFile = runCatching {
            val f = java.io.File(ctx.filesDir, "adclose.txt")
            if (f.exists()) f.readLines()
                .filter { it.isNotBlank() && !it.trim().startsWith("#") && !it.trim().startsWith("!") }
                .map { DomainExclusions.normalize(it) }
                .filter { it.isNotBlank() }
            else emptyList()
        }.getOrDefault(emptyList())
        val fromRules = RuleStore.all().filter { it.domain == Domain.HOST }.map { it.target.trim() }
        (fromFile + fromRules).filter { it.isNotBlank() }.distinct().sorted()
    }
    val shown = remember(query, editRev, excluded) {
        val q = query.trim()
        val live = domains.filter { it !in excluded }
        if (q.isBlank()) live else live.filter { it.contains(q, ignoreCase = true) }
    }

    Kit.DetailPage(title = "域名表", subtitle = "${shown.size} 条", onBack = { Navigator.back() }) {
        item {
            Kit.Group {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索域名",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Kit.Note("命中后解析到 0.0.0.0。移除单条后重新落位，误屏蔽可以恢复。")
        }
        if (excluded.isNotEmpty()) {
            item {
                Kit.RowGroup {
                    Kit.PlainRow(
                        title = "已排除 ${excluded.size} 条",
                        summary = "点这里全部恢复（重新导入订阅也不会把移除的带回来）",
                        onClick = {
                            DomainExclusions.clear()
                            RuleStore.reland()
                            editRev++
                        },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Kit.Empty(if (query.isBlank()) "域名表是空的" else "没找到「${query.trim()}」")
            }
        } else {
            items(shown, key = { it }) { d ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "移除",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickable {
                                DomainExclusions.add(d)
                                RuleStore.reland()
                                editRev++
                            },
                    )
                }
            }
        }
    }
}
