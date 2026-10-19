package com.adhammer.ai

import android.content.Context

/**
 * AI 配置：填了接口信息就能用 AI 根据布局快照自动写规则。
 *
 * 兼容 OpenAI 风格的 /v1/chat/completions 接口，
 * 因此 OpenAI、DeepSeek、通义、Kimi、自建 One-API 等都能直接填。
 */
object AiConfig {

    private const val PREF = "adhammer_ai"
    private const val K_BASE = "base_url"
    private const val K_KEY = "api_key"
    private const val K_MODEL = "model"

    private const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    private const val DEFAULT_MODEL = "deepseek-chat"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context) = prefs(ctx).getString(K_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
    fun apiKey(ctx: Context) = prefs(ctx).getString(K_KEY, "") ?: ""
    fun model(ctx: Context) = prefs(ctx).getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun save(ctx: Context, base: String, key: String, model: String) {
        prefs(ctx).edit()
            .putString(K_BASE, base.trim().ifBlank { DEFAULT_BASE })
            .putString(K_KEY, key.trim())
            .putString(K_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }

    fun isReady(ctx: Context) = apiKey(ctx).isNotBlank()
}
