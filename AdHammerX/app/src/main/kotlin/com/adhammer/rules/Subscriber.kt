package com.adhammer.rules

import android.content.SharedPreferences
import com.adhammer.rules.model.AppConfig
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RulePack
import com.adhammer.rules.model.RuleTypes
import com.adhammer.rules.model.SubIndex
import com.adhammer.rules.model.Task
import com.adhammer.util.HLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 订阅管理：拉取订阅 → 校验 → 解析 → 写入 [RuleStore]。
 *
 * 规则与代码完全解耦：换订阅地址即可换整套规则，不需要改代码。
 *
 * 订阅索引格式：
 * {"full_update":{"zip_md5":"...","zip_url":"...","update_time":"..."}}
 *
 * zip 内的文件按「功能」划分（悟空/八戒/沙僧各自独立）。
 */
object Subscriber {

    const val DEFAULT_SUB_URL = "https://dy.940622.xyz/dy_ys10.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    /** 订阅结果 */
    data class Result(
        val ok: Boolean,
        val message: String,
        val appCount: Int = 0,
        val ruleCount: Int = 0,
        val taskCount: Int = 0,
        val hostCount: Int = 0
    )

    /** 解压后的完整规则集 */
    data class Bundle(
        val wuKongRules: List<RulePack> = emptyList(),
        val wuKongUrls: List<RulePack> = emptyList(),
        val wuKongViews: List<RulePack> = emptyList(),
        val wuKongComponents: List<RulePack> = emptyList(),
        val wuKongReplaces: List<RulePack> = emptyList(),
        val baJieTasks: List<Task> = emptyList(),
        val baJieAutoSkip: Set<String> = emptySet(),
        val shaSengHosts: List<String> = emptyList(),
        val systemHosts: List<String> = emptyList(),
        val appConfig: AppConfig? = null
    )

    // ---------- 下载并应用 ----------

    fun sync(prefs: SharedPreferences, subUrl: String = DEFAULT_SUB_URL, cacheDir: File): Result {
        return try {
            HLog.i("sync start: $subUrl")

            val indexJson = get(subUrl) ?: return Result(false, "订阅索引下载失败")
            val index = runCatching { gson.fromJson(indexJson, SubIndex::class.java) }.getOrNull()
            val zipUrl = index?.fullUpdate?.zipUrl
                ?: return Result(false, "订阅索引格式错误（缺少 zip_url）")
            val expectMd5 = index.fullUpdate?.zipMd5

            val zipFile = File(cacheDir, "sub_rules.zip")
            if (!download(zipUrl, zipFile)) return Result(false, "规则包下载失败")

            val actualMd5 = md5(zipFile)
            if (!expectMd5.isNullOrBlank() && !actualMd5.equals(expectMd5, true)) {
                HLog.w("md5 mismatch: expect=$expectMd5 actual=$actualMd5")
                // 不阻断，仅告警（订阅方可能更新后没同步 md5）
            }

            val bundle = parse(zipFile)
            apply(prefs, bundle, subUrl, actualMd5, index.fullUpdate?.updateTime)

            val ruleCount = (bundle.wuKongRules + bundle.wuKongUrls + bundle.wuKongViews +
                    bundle.wuKongComponents + bundle.wuKongReplaces)
                .sumOf { sanitizePack(it).rules.size }
            val appCount = (bundle.wuKongRules + bundle.wuKongUrls + bundle.wuKongViews +
                    bundle.wuKongComponents + bundle.wuKongReplaces)
                .map { it.pkg }.distinct().size

            HLog.i("sync done: apps=$appCount rules=$ruleCount tasks=${bundle.baJieTasks.size} hosts=${bundle.shaSengHosts.size}")
            Result(true, "同步成功", appCount, ruleCount, bundle.baJieTasks.size, bundle.shaSengHosts.size)
        } catch (e: Throwable) {
            HLog.e("sync failed", e)
            Result(false, e.message ?: "未知错误")
        }
    }

    /** 把规则集按包名聚合后写入存储。 */
    fun apply(prefs: SharedPreferences, b: Bundle, url: String, md5: String?, time: String?) {
        // 按包名聚合悟空的所有规则
        val byPkg = mutableMapOf<String, MutableList<RulePack>>()
        listOf(b.wuKongRules, b.wuKongUrls, b.wuKongViews, b.wuKongComponents, b.wuKongReplaces)
            .flatten()
            .forEach { pack ->
                if (pack.pkg.isBlank()) return@forEach
                byPkg.getOrPut(pack.pkg) { mutableListOf() }.add(pack)
            }
        byPkg.forEach { (pkg, packs) -> RuleStore.putRules(prefs, pkg, packs) }

        // 八戒任务按包名聚合
        val tasksByPkg = mutableMapOf<String, MutableList<Task>>()
        b.baJieTasks.forEach { t ->
            if (t.pkg.isBlank()) return@forEach
            tasksByPkg.getOrPut(t.pkg) { mutableListOf() }.add(t)
        }
        tasksByPkg.forEach { (pkg, tasks) -> RuleStore.putTasks(prefs, pkg, tasks) }

        RuleStore.putHosts(prefs, (b.shaSengHosts + b.systemHosts).distinct())
        RuleStore.putAutoSkip(prefs, b.baJieAutoSkip)
        RuleStore.putMeta(prefs, url, md5, time)
    }

    // ---------- 解析 ----------

    fun parse(zip: File): Bundle {
        val texts = mutableMapOf<String, String>()
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    texts[entry.name.substringAfterLast('/')] = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return Bundle(
            wuKongRules = parsePacks(texts["wu_kong_rules.txt"]),
            wuKongUrls = parsePacks(texts["wu_kong_block_url.txt"]),
            wuKongViews = parsePacks(texts["wu_kong_block_view.txt"]),
            wuKongComponents = parsePacks(texts["wu_kong_block_component.txt"]),
            wuKongReplaces = parsePacks(texts["wu_kong_replaces.txt"]),
            baJieTasks = parseTasks(texts["ba_jie_tasks.txt"]),
            baJieAutoSkip = (texts["ba_jie_auto_skip.txt"] ?: "")
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            shaSengHosts = parseHosts(texts["sha_seng_hosts.txt"]),
            systemHosts = parseHosts(texts["system_hosts.txt"]),
            appConfig = runCatching {
                gson.fromJson(texts["app_config.txt"], AppConfig::class.java)
            }.getOrNull()
        )
    }

    /**
     * 解析 JSON 数组。
     *
     * 两条韧性设计（都是被订阅源教育出来的）：
     * 1. 整包解析失败时**逐元素重试** —— 订阅源里只要有一条字段类型对不上，
     *    以前会把整个文件丢成空列表（八戒任务 327 条全没），现在最多丢坏的那条
     * 2. 单条失败也只记日志，不往上抛
     */
    private inline fun <reified T> fromJsonArray(json: String?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        runCatching { gson.fromJson<List<T>>(json, type) }
            .getOrNull()?.let { return it.filterNotNull() }

        HLog.w("array parse failed, fall back to per-item: ${T::class.java.simpleName}")
        val arr = runCatching {
            gson.fromJson<com.google.gson.JsonArray>(
                json, com.google.gson.JsonArray::class.java
            )
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<T>(arr.size())
        var bad = 0
        arr.forEach { el ->
            runCatching { gson.fromJson<T>(el, T::class.java) }
                .onSuccess { if (it != null) out.add(it) else bad++ }
                .onFailure { bad++ }
        }
        if (bad > 0) HLog.w("dropped $bad bad item(s) in ${T::class.java.simpleName}")
        return out
    }

    /**
     * 订阅源里 `"rules"` / `"steps"` 偶尔是 null。
     * Gson 不会执行 Kotlin 的默认值，反序列化出来就是 null，
     * 后面任何一次 `.rules.size` 都会 NPE —— 订阅后闪退就是这么来的。
     * 所以在解析出口统一兜底。
     */
    private fun parsePacks(json: String?): List<RulePack> =
        fromJsonArray<RulePack>(json).map { sanitizePack(it) }

    private fun parseTasks(json: String?): List<Task> =
        fromJsonArray<Task>(json).map { sanitizeTask(it) }

    /**
     * hosts 文件解析。
     * 两种格式都支持：
     * - `domain`
     * - `127.0.0.1 domain`
     * `#` 开头是注释（订阅方用它标记分组，如 `#pkg=xxx`）。
     */
    private fun parseHosts(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) parts.last() else line
            }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()
    }

    // ---------- 网络 ----------

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrElse {
        HLog.w("get failed: ${it.message}")
        null
    }

    private fun download(url: String, out: File): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return false
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(out).use { fos -> input.copyTo(fos) }
            } ?: return false
            true
        }
    }.getOrElse {
        HLog.w("download failed: ${it.message}")
        false
    }

    private fun md5(file: File): String = MessageDigest.getInstance("MD5")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    /** 统计某个包命中的规则条数，供 UI 展示。 */
    fun countOf(packs: List<RulePack>): Int = packs.sumOf { sanitizePack(it).rules.size }

    /** 取某条规则的展示名。 */
    fun titleOf(item: RuleItem): String = item.ad ?: "(无)"
}
