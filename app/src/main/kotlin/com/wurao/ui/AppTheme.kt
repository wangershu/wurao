package com.wurao.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 主题档位（外观页 RadioGroup 的顺序就是它） */
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * 界面偏好：主题 + 悬浮底栏。
 *
 * 必须是 Compose state（底栏和主题都订阅它）；落盘用 SharedPreferences，
 * 键名沿用旧版（floating_bar），已有设置不丢。
 */
object UiPrefs {
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var floatingBar by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(com.wurao.core.Cfg.GROUP, Context.MODE_PRIVATE)
        themeMode = when (p.getString("theme_mode", "system")) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        floatingBar = p.getBoolean("floating_bar", true)
    }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        themeMode = mode
        val key = when (mode) {
            ThemeMode.SYSTEM -> "system"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
        ctx.getSharedPreferences(com.wurao.core.Cfg.GROUP, Context.MODE_PRIVATE)
            .edit().putString("theme_mode", key).apply()
    }

    fun setFloatingBar(ctx: Context, on: Boolean) {
        floatingBar = on
        ctx.getSharedPreferences(com.wurao.core.Cfg.GROUP, Context.MODE_PRIVATE)
            .edit().putBoolean("floating_bar", on).apply()
    }
}

/**
 * 应用主题。
 *
 * 用 `MiuixTheme(controller = ThemeController(...))` 而不是自己传 colors ——
 * 传 colors 的写法会绕过 miuix 的默认 typography（连带字体族），
 * 在这台机器上表现为数字全渲染成豆腐块。
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
