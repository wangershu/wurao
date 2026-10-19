package com.adhammer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 应用主题：miuix 的 MIUI 配色，深浅色由 [UiPrefs.themeMode] 决定。
 *
 * 颜色一律从 MiuixTheme.colorScheme 取，业务代码里不硬编码色值 ——
 * 这样切深色、或以后接 Monet 取色，都不用改界面代码。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (UiPrefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
