package com.adhammer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adhammer.rules.StatsStore
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val C_VIEW = Color(0xFF30B0C7)
private val C_SKIP = Color(0xFFFF9F0A)
private val C_HOST = Color(0xFF34C759)

/**
 * 统计：跳过了什么、拦了多少次、哪个 App 最吵。
 *
 * 参考原版大圣的统计维度（总跳过次数、按应用），这里补了趋势与占比两张图。
 * 数据来自 hook 进程每次命中打的点（[StatsStore]），不是日志明细，所以能长期累计。
 */
@Composable
fun StatsScreen() {
    LaunchedEffect(Unit) { UiState.refreshStats() }

    val kinds = listOf(
        "页面拦截" to UiState.statKinds[StatsStore.KIND_PAGE],
        "View 隐藏" to UiState.statKinds[StatsStore.KIND_VIEW],
        "自动跳过" to UiState.statKinds[StatsStore.KIND_SKIP],
        "域名拦截" to UiState.statKinds[StatsStore.KIND_HOST],
    ).map { it.first to (it.second ?: 0L) }

    val ringColors = listOf(
        MiuixTheme.colorScheme.primary, C_VIEW, C_SKIP, C_HOST
    )

    Column(modifier = Modifier.fillMaxSize()) {
        SmallTitle(
            text = "统计",
            modifier = Modifier.padding(horizontal = Kit.H_PAD, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Kit.Stats(
                    listOf(
                        "${UiState.statToday}" to "今日",
                        "${UiState.statWeek}" to "近 7 天",
                        "${UiState.statTotal}" to "累计"
                    )
                )
            }

            item { Kit.Section("近 7 天趋势") }
            item { Kit.Group { BarChart(UiState.statDays) } }

            item { Kit.Section("拦截类型占比") }
            item { Kit.Group { RingChart(kinds, ringColors) } }

            item { Kit.Section("应用排行", "Top ${UiState.statApps.size}") }
            if (UiState.statApps.isEmpty()) {
                item { Kit.Empty(text = "还没有命中记录。模块生效后打开目标应用逛一圈，这里就会有数据。") }
            } else {
                item { Kit.Group { RankBars(UiState.statApps) } }
            }

            item { Kit.Section("明细") }
            item {
                Kit.ArrowItem(
                    title = "拦截记录",
                    summary = "${UiState.logs.size} 条最近明细",
                    icon = MiuixIcons.Search,
                    onClick = { Navigator.go(Dest.Logs) }
                )
            }
            item {
                Kit.ArrowItem(
                    title = "清空统计数据",
                    summary = "只清计数，不动规则和日志",
                    icon = MiuixIcons.Search,
                    onClick = { UiState.clearStats() }
                )
            }
        }
    }
}
