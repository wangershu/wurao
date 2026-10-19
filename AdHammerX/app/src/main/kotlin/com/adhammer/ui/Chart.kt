package com.adhammer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 极简图表。
 *
 * 统计页只要三种图：柱状（趋势）、环形（占比）、横条（排行）。
 * 为一个页面引一整个图表库不划算，而且第三方库的配色还得再适配一遍主题，
 * 所以直接用 Canvas 画：颜色一律取 `MiuixTheme.colorScheme`，深浅色自动跟着变。
 */

/** 近 N 天柱状图：data = [(yyyyMMdd, 次数)]，从旧到新 */
@Composable
fun BarChart(data: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return
    val max = data.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val accent = MiuixTheme.colorScheme.primary
    val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(horizontal = 4.dp)
        ) {
            val n = data.size
            val slot = size.width / n
            val barW = slot * 0.46f
            data.forEachIndexed { i, (_, v) ->
                val h = (v.toFloat() / max.toFloat()) * size.height
                val left = slot * i + (slot - barW) / 2
                val top = size.height - h.coerceAtLeast(2f)
                // 零值也画一小截，让人看得出「这天有记录但没命中」
                drawRoundRect(
                    color = if (v > 0) accent else dim.copy(alpha = 0.25f),
                    topLeft = Offset(left, top),
                    size = Size(barW, h.coerceAtLeast(3f)),
                    cornerRadius = CornerRadius(barW / 2, barW / 2)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            data.forEach { (day, v) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (v > 0) v.toString() else "",
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = day.takeIf { it.length == 8 }?.let { "${it.substring(4, 6)}/${it.substring(6)}" } ?: day,
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/** 环形占比图：data = [(名称, 数值)]，colors 一一对应 */
@Composable
fun RingChart(
    data: List<Pair<String, Long>>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.second }.toFloat()
    val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
            Canvas(modifier = Modifier.size(132.dp)) {
                if (total <= 0f) {
                    drawArc(
                        color = dim.copy(alpha = 0.25f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 22.dp.toPx())
                    )
                    return@Canvas
                }
                var start = -90f
                data.forEachIndexed { i, (_, v) ->
                    val sweep = (v.toFloat() / total) * 360f
                    if (sweep > 0f) {
                        drawArc(
                            color = colors.getOrElse(i) { dim },
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = 22.dp.toPx())
                        )
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = total.toLong().toString(),
                    fontSize = 20.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "总拦截",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sum = total.coerceAtLeast(1f)
            data.forEachIndexed { i, (name, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(9.dp)) {
                        drawCircle(color = colors.getOrElse(i) { dim })
                    }
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                    )
                    Text(
                        text = "${(v.toFloat() / sum * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/** 应用排行：横向条 + 次数 */
@Composable
fun RankBars(data: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return
    val max = data.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val accent = MiuixTheme.colorScheme.primary
    val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(modifier = modifier.fillMaxWidth()) {
        data.forEach { (pkg, v) ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = UiState.labelOf(pkg),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = v.toString(),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(top = 4.dp)
                ) {
                    val w = (v.toFloat() / max.toFloat()) * size.width
                    drawRoundRect(
                        color = dim.copy(alpha = 0.2f),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2)
                    )
                    drawRoundRect(
                        color = accent,
                        size = Size(w.coerceAtLeast(4f), size.height),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2)
                    )
                }
            }
        }
    }
}
