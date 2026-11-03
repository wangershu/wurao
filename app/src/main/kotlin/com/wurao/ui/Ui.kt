package com.wurao.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wurao.core.Cfg
import com.wurao.core.model.Rule
import com.wurao.core.net.DomainSet
import com.wurao.core.source.GkdSubscription
import com.wurao.core.store.RuleStore

/**
 * 界面层的业务胶水：规则库读写 + 推送 + 导入 + 备份。
 *
 * 这些函数原来长在 MainActivity 上，拆页面时原样搬到这里（逻辑一行没改，
 * 只是 `this@MainActivity` 换成了 ctx 参数），让 Home/Apps/Settings 不依赖 Activity。
 */
object Ui {

    /**
     * 规则库是否已加载完。加载是同步的（MainActivity.onCreate 里），
     * 保留这个状态是为了「直达规则变动 → 界面刷新」的触发源。
     */
    var rulesLoaded by mutableStateOf(false)

    /** 规则版本号：任何规则变动后 +1，订阅它的页面会自动重组。 */
    var ruleRev by mutableStateOf(0)

    /** 命中记录版本号：同上。 */
    var hitRev by mutableStateOf(0)

    fun prefs(ctx: Context) =
        ctx.getSharedPreferences(Cfg.GROUP, Context.MODE_PRIVATE)

    /**
     * 规则有任何改动都要调一次：写统一键并推送到 hook 进程。
     *
     * **放后台**：几万条规则时，编码 + 写配置 + 推文件这一串在主线程是好几秒的活，
     * 实测就是这么 ANR 的（主线程被卡住 5 秒以上）。
     */
    fun pushRulesAsync(ctx: Context) {
        Thread {
            val all = RuleStore.all()
            prefs(ctx).edit().putString(Cfg.KEY_RULES, Cfg.encodeRules(all)).apply()
            CfgPush.flush(ctx)
            Handler(Looper.getMainLooper()).post { ruleRev++ }
        }.start()
    }

    /**
     * 把落盘的规则读回规则库。
     *
     * 第一次还会把旧的两份（user_rules / sub_rules）迁进来 ——
     * 你已经配好的规则不能因为这次统一而丢掉。
     */
    fun restoreRules(ctx: Context) {
        val unified = Cfg.decodeRules(prefs(ctx).getString(Cfg.KEY_RULES, null))
        if (unified.isNotEmpty()) {
            unified.forEach { RuleStore.upsert(it) }
            return
        }
        val legacy = Cfg.decodeRules(prefs(ctx).getString(Cfg.KEY_USER_RULES, null)) +
            Cfg.decodeRules(prefs(ctx).getString(Cfg.KEY_SUB_RULES, null))
        if (legacy.isEmpty()) return
        legacy.forEach { RuleStore.upsert(it) }
        pushRulesAsync(ctx)
        Log.i("Wurao", "规则迁移：${legacy.size} 条 -> 统一键")
    }

    /**
     * 导入一份源：认得出是 GKD 订阅还是域名表，解析后存进订阅那一份。
     *
     * 两条规矩：
     * - **不静默**：跳过了多少、为什么跳过，都要写进报告给你看；
     * - 只 replace 订阅那一份，**不动你自己抓的规则**。
     */
    fun importSource(ctx: Context, text: String): String {
        // 第三种源：内部规则文本（tools/ 里的转换脚本产出的格式）。
        // 用来把旧规则库的结论搬进来 —— 只搬名单，旧格式的解析代码不进 App。
        val looksLikeInternal = text.contains("\t") && text.lineSequence().firstOrNull()
            ?.split("\t")?.getOrNull(1)?.let { com.wurao.core.model.Act.fromKey(it.trim()) != null } == true
        if (looksLikeInternal) {
            val list = Cfg.decodeRules(text)
            list.forEach { RuleStore.upsert(it) }
            pushRulesAsync(ctx)
            return "规则文本：读入 ${list.size} 条；库里现在共 ${RuleStore.all().size} 条"
        }

        // 认类型的依据必须是"长什么样"，不能是"含不含某个词" ——
        // 之前写的是 contains("apps")，而域名里常有 apps.xxx.com，
        // 于是域名表被误判成 GKD 订阅拿去当 JSON5 解析，整片静默报废
        // （表现就是"导入了但只进了一半"，且不报错）
        val firstLine = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") } ?: ""
        val looksLikeSub = firstLine.startsWith("{") || firstLine.startsWith("[{")
        val head: String
        if (looksLikeSub) {
            val r = GkdSubscription.parse(text)
            val report = RuleStore.import(r)
            val reasons = r.skipped.take(3).joinToString("；") { it.reason }
            head = "订阅共 ${r.total} 条：采用 ${report.added}，跳过 ${report.skipped}" +
                (if (r.skipped.isNotEmpty()) "（例如：$reasons）" else "") +
                "；库里现在共 ${RuleStore.all().size} 条"
        } else {
            val h = DomainSet.parse(text)
            h.forEach { domain ->
                RuleStore.upsert(
                    Rule(
                        domain = com.wurao.core.model.Domain.HOST,
                        target = domain,
                        act = com.wurao.core.model.Act.BLOCK,
                        scope = com.wurao.core.model.Scope.Global,
                        origin = com.wurao.core.model.Origin.Subscription(0),
                        groupKey = "hosts",
                        ruleKey = domain.hashCode().toString(),
                        note = "广告域名",
                    )
                )
            }
            pushRulesAsync(ctx)
            head = "域名表：读入 ${h.size} 个域名（已去重）；库里现在共 ${RuleStore.all().size} 条"
        }
        pushRulesAsync(ctx)
        return head
    }

    /**
     * 从网址导入：下载与解析都放后台线程 —— 订阅动辄 1MB，在 UI 线程解析会卡住界面。
     * 必须设超时：不设的话网络不通时会一直挂着，看起来就是「点了没反应」。
     */
    fun importSourceUrl(ctx: Context, url: String, done: (String) -> Unit) {
        Thread {
            val started = System.currentTimeMillis()
            val result = runCatching {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 15_000
                conn.readTimeout = 25_000
                conn.getInputStream().bufferedReader().readText()
            }
            val report = result.fold(
                onSuccess = { body ->
                    if (body.isBlank()) {
                        "下载内容为空"
                    } else {
                        val r = importSource(ctx, body)
                        "$r；耗时 ${System.currentTimeMillis() - started}ms，原文 ${body.length} 字符"
                    }
                },
                onFailure = { e ->
                    "下载失败：${e.javaClass.simpleName} ${e.message?.take(70) ?: ""}"
                }
            )
            Handler(Looper.getMainLooper()).post { done(report) }
        }.start()
    }

    /** 跑到后台线程再回主线程报结果（调试直达的导入走这里）。 */
    fun runInBackground(ctx: Context, onDone: (() -> Unit)? = null, work: () -> String) {
        Thread {
            val report = runCatching { work() }.onFailure {
                Log.w("Wurao", "导入异常: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault("导入失败")
            Handler(Looper.getMainLooper()).post {
                Log.i("Wurao", "import: $report")
                Toast.makeText(ctx, report, Toast.LENGTH_LONG).show()
                onDone?.invoke()
            }
        }.start()
    }

    /**
     * 备份成一段文本（所有配置的键值对）。
     *
     * 用文本而不是导出文件：不用申请存储权限，你复制出来贴到任何地方都行。
     */
    fun exportText(ctx: Context): String {
        val o = org.json.JSONObject()
        prefs(ctx).all.forEach { (k, v) -> o.put(k, v?.toString() ?: "") }
        return o.toString()
    }

    /** 从备份文本恢复。整份覆盖 —— 所以恢复前会先告诉你里面有多少项。 */
    fun restoreText(ctx: Context, text: String): String {
        val o = runCatching { org.json.JSONObject(text) }.getOrElse {
            return "这段不是有效的备份（解析失败）"
        }
        val e = prefs(ctx).edit()
        var n = 0
        o.keys().forEach { k ->
            e.putString(k, o.optString(k))
            n++
        }
        e.apply()
        CfgPush.flush(ctx)
        return "已恢复 $n 项配置"
    }
}

/** 多久之前。命中记录只看次数的话，分不清是刚才拦的还是上周拦的。 */
fun timeAgo(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3_600_000} 小时前"
        d < 7 * 86_400_000L -> "${d / 86_400_000} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}

/** 动作的中文说法（模型里只有英文 key，界面上得说人话）。 */
fun actCn(act: com.wurao.core.model.Act): String = when (act.key) {
    "click" -> "点掉"
    "long_click" -> "长按"
    "hide" -> "藏起来"
    "remove" -> "摘掉"
    "block" -> "拦住"
    "goto" -> "跳走"
    "back" -> "返回"
    else -> act.key
}

/** 域的中文说法。 */
fun domainCn(key: String): String = when (key) {
    "node" -> "控件"
    "host" -> "域名"
    "component" -> "组件"
    "sdk" -> "SDK"
    "sensor" -> "传感器"
    "file" -> "文件"
    else -> key
}
