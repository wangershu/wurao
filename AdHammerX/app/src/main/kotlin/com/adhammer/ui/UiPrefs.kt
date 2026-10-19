package com.adhammer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 主题模式 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

/**
 * 界面偏好：主题、底栏形态。
 *
 * 这些只是「外壳」，和规则无关，所以单独存一份，不进远程偏好（hook 进程不需要知道）。
 */
object UiPrefs {

    private const val PREF = "adhammer_ui"
    private const val K_THEME = "theme"
    private const val K_FLOAT_BAR = "float_bar"

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** true = 悬浮圆角底栏；false = 贴底整条底栏 */
    var floatingBar by mutableStateOf(true)
        private set

    fun init(ctx: Context) {
        val p = prefs(ctx)
        themeMode = runCatching { ThemeMode.valueOf(p.getString(K_THEME, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        floatingBar = p.getBoolean(K_FLOAT_BAR, true)
    }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        themeMode = mode
        prefs(ctx).edit().putString(K_THEME, mode.name).apply()
    }

    fun setFloatingBar(ctx: Context, on: Boolean) {
        floatingBar = on
        prefs(ctx).edit().putBoolean(K_FLOAT_BAR, on).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
