package com.adhammer.analyzer

import android.content.Context
import com.adhammer.rules.Jsonx
import com.adhammer.util.HLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 广告特征库 —— 本地 APK 分析的「知识」来源。
 *
 * 这是**不依赖大模型**的关键：把「哪些类名/资源名/域名像广告」沉淀成一份可维护的 JSON，
 * 分析器只做匹配。特征库可以：
 * 1. 用内置的（assets/ad_features.json）
 * 2. 从订阅地址下载 ad_features.json 覆盖（和规则订阅同一套更新机制）
 * 3. 在设置页手工粘贴 JSON 导入
 *
 * 规则要长期维护，维护的其实是这份特征库，而不是一条条规则。
 */
data class SdkFeature(
    val id: String = "",
    val name: String = "",
    val prefixes: List<String> = emptyList()
)

data class FeaturePack(
    val version: String = "",
    val updateTime: String = "",
    val sdks: List<SdkFeature> = emptyList(),
    val activityWords: List<String> = emptyList(),
    val safeWords: List<String> = emptyList(),
    val resWords: List<String> = emptyList(),
    val domainWords: List<String> = emptyList(),
    val domainExclude: List<String> = emptyList(),
    /** 来源：内置 / 订阅 / 手工 */
    val source: String = "内置"
)

object AdFeatures {

    private const val FILE = "ad_features.json"

    @Volatile
    private var current: FeaturePack? = null

    fun get(ctx: Context): FeaturePack {
        current?.let { return it }
        val loaded = load(ctx)
        current = loaded
        return loaded
    }

    private fun load(ctx: Context): FeaturePack {
        // 本地覆盖优先（订阅或手工导入过的）
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) {
                val txt = f.readText()
                Jsonx.gson.fromJson(txt, FeaturePack::class.java)?.let { pack ->
                    if (pack.sdks.isNotEmpty()) return pack.copy(source = "已更新")
                }
            }
        }.onFailure { HLog.w("load local features failed: ${it.message}") }

        runCatching {
            ctx.assets.open(FILE).use { it.readBytes().toString(Charsets.UTF_8) }.let { txt ->
                Jsonx.gson.fromJson(txt, FeaturePack::class.java)?.let { return it }
            }
        }.onFailure { HLog.w("load asset features failed: ${it.message}") }

        return builtIn()
    }

    /** 用新 JSON 覆盖（订阅或手工导入），返回是否成功 */
    fun update(ctx: Context, json: String): Boolean {
        val pack = runCatching { Jsonx.gson.fromJson(json, FeaturePack::class.java) }
            .onFailure { HLog.w("parse features failed: ${it.message}") }
            .getOrNull()
        if (pack == null || pack.sdks.isEmpty()) return false
        return runCatching {
            File(ctx.filesDir, FILE).writeText(json)
            current = pack.copy(source = "已更新")
            true
        }.onFailure { HLog.w("save features failed: ${it.message}") }.getOrDefault(false)
    }

    fun reset(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
        current = null
    }

    /** 从订阅地址拉一份新的特征库（和规则订阅用同一套更新机制） */
    suspend fun fetchUpdate(ctx: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val txt = resp.body?.string() ?: return@withContext false
                update(ctx, txt)
            }
        }.onFailure { HLog.w("fetch features failed: ${it.message}") }.getOrDefault(false)
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 兜底：assets 都读不到时至少能跑起来 */
    private fun builtIn() = FeaturePack(
        version = "builtin",
        updateTime = "",
        sdks = listOf(
            SdkFeature("pangle", "穿山甲", listOf("com.bytedance.sdk.openadsdk", "com.bytedance.pangle")),
            SdkFeature("gdt", "优量汇", listOf("com.qq.e.", "com.tencent.gdt")),
            SdkFeature("admob", "AdMob", listOf("com.google.android.gms.ads")),
            SdkFeature("baidu", "百度联盟", listOf("com.baidu.mobads")),
            SdkFeature("kuaishou", "快手联盟", listOf("com.kwad.sdk")),
            SdkFeature("sigmob", "Sigmob", listOf("com.sigmob.sdk"))
        ),
        activityWords = listOf("Ad", "Advert", "Splash", "Interstitial", "Reward", "Banner"),
        safeWords = listOf("Main", "Home", "Launcher", "Index", "Tab", "Root"),
        resWords = listOf("skip", "close", "ad_container", "ad_layout", "countdown"),
        domainWords = listOf("ad.", "ads.", "advert", "gdt", "pangle", "sigmob", "mobads"),
        domainExclude = listOf("googleapis", "gstatic", "android.com", "w3.org"),
        source = "内置兜底"
    )
}
