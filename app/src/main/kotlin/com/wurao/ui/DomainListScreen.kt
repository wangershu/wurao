package com.wurao.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wurao.core.model.Domain
import com.wurao.core.store.RuleStore
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 域名表子页面：把 adclose.txt 的全部域名列出来，可搜索。
 * LazyColumn 本身按需组合，1.7 万条也不会一次性堆 Card。
 */
@Composable
fun DomainListScreen() {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val domains = remember {
        val fromFile = runCatching {
            val f = java.io.File(ctx.filesDir, "adclose.txt")
            if (f.exists()) f.readLines().map { raw ->
                val t = raw.trim()
                // AdClose 格式是 "domain, xxx.com"；也有纯域名写法
                when {
                    t.startsWith("domain,", ignoreCase = true) -> t.removePrefix("domain,").trim()
                    t.contains(",") -> t.substringAfter(",").trim()
                    else -> t
                }
            }.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("!") }
            else emptyList()
        }.getOrDefault(emptyList())
        val fromRules = RuleStore.all().filter { it.domain == Domain.HOST }.map { it.target.trim() }
        (fromFile + fromRules).filter { it.isNotBlank() }.distinct().sorted()
    }
    val shown = remember(query) {
        val q = query.trim()
        if (q.isBlank()) domains else domains.filter { it.contains(q, ignoreCase = true) }
    }

    Kit.DetailPage(title = "域名表", subtitle = "${domains.size} 条", onBack = { Navigator.back() }) {
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
            Kit.Note(
                "共 ${domains.size} 条，命中后解析到 0.0.0.0。" +
                    if (query.isBlank()) "" else " 匹配 ${shown.size} 条。"
            )
        }
        if (shown.isEmpty()) {
            item {
                Kit.Empty(if (query.isBlank()) "域名表是空的" else "没找到「${query.trim()}」")
            }
        } else {
            items(shown, key = { it }) { d ->
                Text(
                    d,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}
