package com.adhammer.ai

import android.content.Context
import com.adhammer.util.HLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 自动写规则。
 *
 * 输入：布局快照（悬浮窗抓取的 View 树）+ 目标包名
 * 输出：符合订阅格式的规则 JSON，可直接保存
 *
 * 用系统提示词把规则 schema 讲清楚，让模型只输出 JSON，避免解析失败。
 */
object AiRuleGen {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM = """
你是一个 Android 去广告规则生成专家。根据给定的布局快照（View 树），生成用于屏蔽广告的规则 JSON。

规则 schema（必须严格遵守）：
{
  "pkg": "目标包名",
  "ruleType": 14,        // 14=View屏蔽 15=组件屏蔽 10=通用规则 11=URL拦截 20=文件替换
  "isEnable": true,
  "rules": [
    {
      "actionType": 8,                    // 8=精准View屏蔽 9=防火墙拦截 500=屏蔽Activity
      "ad": "目标Activity全名",
      "adKey": "[{\"v\":\"android.widget.TextView\",\"s\":true,\"id\":\"xxx\",\"t\":\"跳过\",\"ht\":1,\"pl\":0,\"cl\":0}]",
      "delay": 0,
      "isEnable": true,
      "times": 1,
      "remark": "说明"
    }
  ]
}

adKey 字段说明（actionType=8 时）：
- v: 入口 View 类名前缀，必填
- s: 开关，true
- id: 资源名（不含包名前缀），多个候选用 ; 分隔
- t: 文本，多个候选用 ; 分隔，包含匹配；#文本# 表示全等
- ht: 1
- pl: 向上找几层父容器（隐藏整个广告容器时用）
- cl: 向下找几层子 View
- sq: 子 View 序号，多个用 ; 分隔

要求：
1. 优先选最底层的 View，pl 用于向外扩展到整个广告容器
2. 只输出一个 JSON 对象，不要任何解释文字、不要 markdown 代码块
3. 不确定时给最保守的规则（只屏蔽明确是广告的部分）
"""

    /** 生成规则，返回 JSON 字符串；失败返回 null */
    suspend fun generate(ctx: Context, pkg: String, snapshot: String, wish: String = ""): String? =
        withContext(Dispatchers.IO) {
            if (!AiConfig.isReady(ctx)) {
                HLog.w("AI not configured")
                return@withContext null
            }
            val body = JSONObject().apply {
                put("model", AiConfig.model(ctx))
                put("temperature", 0.2)
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM))
                    put(JSONObject().put("role", "user").put("content",
                        "目标包名：$pkg\n${if (wish.isNotBlank()) "需求：$wish\n" else ""}布局快照：\n$snapshot"))
                }
                put("messages", messages)
            }

            val req = Request.Builder()
                .url(AiConfig.baseUrl(ctx).trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer ${AiConfig.apiKey(ctx)}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            return@withContext runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        HLog.w("AI http ${resp.code}: ${resp.body?.string()?.take(200)}")
                        return@use null
                    }
                    val text = resp.body?.string() ?: return@use null
                    val content = JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    extractJson(content)
                }
            }.onFailure { HLog.e("AI generate failed", it) }.getOrNull()
        }

    /** 模型可能带上 ```json 包裹，这里剥掉 */
    private fun extractJson(text: String): String? {
        val t = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return t.substring(s, e + 1)
    }
}
