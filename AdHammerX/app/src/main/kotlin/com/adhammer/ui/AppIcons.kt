package com.adhammer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自绘图标。
 *
 * miuix 的 `MiuixIcons` 里有 156 个官方图标（Home / GridView / Settings / Info …），
 * 底栏直接复用；但图集里**没有图表类图标**，统计页那个柱状图是自己画的，
 * 风格跟着 miuix 走：24dp 网格、纯填充、端点全圆角。
 */
object AppIcons {

    /** 统计：三根由低到高的圆角柱 */
    val Stats: ImageVector by lazy {
        Icon(
            "Stats",
            // 低柱
            "M5,13a2,2 0 0 1 2,2v4a2,2 0 0 1 -2,2a2,2 0 0 1 -2,-2v-4a2,2 0 0 1 2,-2z",
            // 高柱
            "M11.5,5a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2a2,2 0 0 1 -2,-2V7a2,2 0 0 1 2,-2z",
            // 中柱
            "M18,9a2,2 0 0 1 2,2v8a2,2 0 0 1 -2,2a2,2 0 0 1 -2,-2v-8a2,2 0 0 1 2,-2z"
        )
    }

    /** 净化：漏斗（过滤广告） */
    val Purify: ImageVector by lazy {
        Icon(
            "Purify",
            "M3.4,5.2a1.1,1.1 0 0 1 0.8,-1.7h15.6a1.1,1.1 0 0 1 0.78,1.88L14.5,12.9v6.2a1.1,1.1 0 0 1 -1.5,1.02l-2.6,-1.1a1.1,1.1 0 0 1 -0.67,-1.01v-5.11Zm1.23,0L10.2,11.9v6.86l1.6,0.68v-7.54Z"
        )
    }

    private fun Icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            paths.forEach {
                // 这一版 compose 的 addPath 收的是 PathNode 列表，字符串要先解析
                addPath(
                    pathData = PathParser().parsePathString(it).toNodes(),
                    fill = SolidColor(Color.Black)
                )
            }
        }.build()
}
