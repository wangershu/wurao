package com.adhammer.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 应用主题：miuix 的 MIUI 配色，深浅色由 [UiPrefs.themeMode] 决定。
 *
 * 颜色一律从 MiuixTheme.colorScheme 取，业务代码里不硬编码色值 ——
 * 这样切深色、或以后接 Monet 取色，都不用改界面代码。
 *
 * 顺带做 edge-to-edge：参考项目 HyperIsland 就是这么做的（系统栏透明、内容铺到底），
 * 不做的话顶部会多出一条系统栏留白的色带，跟参考站在一起会很明显。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (UiPrefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val activity = LocalActivity.current as? ComponentActivity
    SideEffect {
        val barStyle = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        runCatching {
            activity?.enableEdgeToEdge(
                statusBarStyle = barStyle,
                navigationBarStyle = barStyle
            )
        }
    }
    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
