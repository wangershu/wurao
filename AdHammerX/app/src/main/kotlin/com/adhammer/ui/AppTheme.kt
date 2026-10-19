package com.adhammer.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主题。
 *
 * 用 `MiuixTheme(controller = ThemeController(...))` 而不是自己传 colors ——
 * 跟参考项目 HyperIsland 完全一致。之前传 colors 的写法会绕过 miuix 的
 * 默认 typography（连带字体族），在这台机器上表现为数字全渲染成豆腐块。
 *
 * 顺带做 edge-to-edge：系统栏透明、内容铺到底，不做的话顶部会多一条色带。
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
    val controller = ThemeController(
        colorSchemeMode = when (UiPrefs.themeMode) {
            ThemeMode.SYSTEM -> ColorSchemeMode.System
            ThemeMode.LIGHT -> ColorSchemeMode.Light
            ThemeMode.DARK -> ColorSchemeMode.Dark
        }
    )
    MiuixTheme(controller = controller, content = content)
}
