package com.adhammer.hook

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.adhammer.rules.StatsStore
import com.adhammer.rules.model.Actions
import com.adhammer.rules.model.RuleItem
import com.adhammer.rules.model.RuleTypes
import com.adhammer.util.HLog
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 悟空：Xposed 侧的去广告主力。
 *
 * 覆盖五类手段：
 * 1. Activity 级 —— 关闭 / 禁用 / 跳首页 / 防火墙拦截（ruleType 10）
 * 2. View 级    —— 精准匹配隐藏（ruleType 14）
 * 3. URL 级     —— 拦截广告请求（ruleType 11）
 * 4. 组件级     —— 屏蔽四大组件（ruleType 15，actionType 500~503）
 * 5. 文件级     —— 替换广告缓存（ruleType 20）
 */
object WuKong {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var curAct: WeakReference<Activity>? = null

    @Volatile
    private var curActName: String = ""

    // ==================== 通用 hook 封装 ====================

    private fun XposedInterface.hookIt(method: Method?, id: String, hooker: (XposedInterface.Chain) -> Any?) {
        if (method == null) {
            HLog.w("hook target missing: $id")
            return
        }
        runCatching {
            hook(method)
                .setId(id)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> hooker(chain) }
        }.onFailure { HLog.w("hook failed $id: ${it.message}") }
    }

    // ==================== 1. Activity 级 ====================

    fun installActivity(base: XposedInterface, pkg: String, cl: ClassLoader) {
        val rules = RuntimeCfg.rulesOf(pkg, RuleTypes.WU_KONG_RULE)
        if (rules.isEmpty()) return

        // 按 Activity 名建索引，hook 路径上不做遍历
        val actMap = HashMap<String, RuleItem>()
        rules.forEach { r -> r.ad?.let { actMap[it] = r } }
        val dialogRules = rules.filter { it.isDialogKey }

        // ① onCreate：广告页已经打开
        base.hookIt(
            Reflect.findMethod(Activity::class.java, "onCreate", Bundle::class.java),
            "wuKong_act_onCreate"
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                val act = chain.getThisObject() as? Activity ?: return@runCatching
                curAct = WeakReference(act)
                curActName = act.javaClass.name
                val rule = actMap[curActName] ?: return@runCatching
                if (!rule.isEnable) return@runCatching
                HLog.d("hit ad activity: $curActName at=${rule.actionType}")
                HitLog.hit(pkg, curActName.substringAfterLast('.'), Actions.describe(rule.actionType), StatsStore.KIND_PAGE)
                handleActivity(act, rule, pkg)
                // 真的跳过了才提示；改跳转目标/自定义 Hook 这类不弹，避免误报
                if (rule.actionType == Actions.CLOSE_ACT ||
                    rule.actionType == Actions.SKIP_TO_HOME ||
                    rule.actionType == Actions.DISABLE_ACT
                ) {
                    SkipTips.show(act, RuntimeCfg.prefs())
                }
            }
            result
        }

        // ② startActivity：广告页正要打开（拦截 / 改写）
        val startHook: (XposedInterface.Chain) -> Any? = { chain ->
            var blocked = false
            var intent: Intent? = null
            for (arg in chain.getArgs()) {
                if (arg is Intent) { intent = arg; break }
            }
            val cn = intent?.component
            // 带 data 的外部唤起不拦，避免破坏正常分享/跳转
            if (cn != null && intent?.data == null) {
                when (actMap[cn.className]?.actionType) {
                    Actions.BLOCK_INTENT -> {
                        HLog.d("block start: ${cn.className}")
                        intent?.setComponent(null)
                        intent?.action = null
                        runCatching { intent?.extras?.clear() }
                        blocked = true   // 不 proceed，启动胎死腹中
                    }
                    Actions.SKIP_TO_HOME, Actions.RETARGET -> {
                        homeComponent(pkg, cn.className)?.let { home ->
                            HLog.d("retarget ${cn.className} -> ${home.className}")
                            intent?.setComponent(home)
                        }
                    }
                }
            }
            if (blocked) null else chain.proceed()
        }

        base.hookIt(
            Reflect.findMethod(ContextWrapper::class.java, "startActivity", Intent::class.java),
            "wuKong_startActivity"
        ) { startHook(it) }

        base.hookIt(
            Reflect.findMethod(Activity::class.java, "startActivityForResult", Intent::class.java, Integer.TYPE),
            "wuKong_startActivityForResult"
        ) { startHook(it) }

        // ③ Dialog 规则
        if (dialogRules.isNotEmpty()) {
            installDialogs(base, pkg, dialogRules)
        }
    }

    /** 按规则处理已经打开的广告页。 */
    private fun handleActivity(act: Activity, rule: RuleItem, pkg: String) {
        when (rule.actionType) {
            Actions.CLOSE_ACT -> {
                // 关闭活动页：d>0 时延迟后按返回键（应对倒计时结束才能跳过的场景）
                if (rule.delay > 0) postDelay(act, rule.delay) { it.onBackPressed() }
                else finishNow(act)
            }
            Actions.DISABLE_ACT -> disableComponent(act, act.javaClass.name)
            Actions.SKIP_TO_HOME -> {
                val home = homeComponent(pkg, rule.ad)
                if (home != null) jumpHome(act, home)
                else finishNow(act)   // 没配首页时降级为关闭，保证规则仍然有效
            }
            Actions.BLOCK_INTENT -> finishNow(act)
            else -> HLog.d("unhandled action ${rule.actionType} on $curActName")
        }
    }

    private fun homeComponent(pkg: String, adName: String?): ComponentName? {
        val home = HomeConfig.get(pkg) ?: return null
        return ComponentName(pkg, home)
    }

    private fun finishNow(act: Activity) {
        runCatching {
            if (act.isFinishing) return
            act.setResult(Activity.RESULT_OK)
            act.finish()
            act.overridePendingTransition(0, 0)
        }
    }

    private fun postDelay(act: Activity, delayMs: Long, block: (Activity) -> Unit) {
        val ref = WeakReference(act)
        mainHandler.postDelayed({ ref.get()?.let(block) }, delayMs)
    }

    private fun jumpHome(act: Activity, home: ComponentName) {
        runCatching {
            if (act.isFinishing) return
            val origin = act.intent
            if (origin?.data != null) return   // 外部唤起不改写
            val intent = Intent()
            if (origin?.extras != null) intent.putExtras(origin.extras!!)
            intent.component = home
            act.startActivity(intent)
            act.setResult(Activity.RESULT_OK)
            act.finish()
            act.overridePendingTransition(0, 0)
        }
    }

    private fun disableComponent(context: android.content.Context, className: String) {
        runCatching {
            val pm = context.packageManager
            val cn = ComponentName(context.packageName, className)
            if (pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
                )
                HLog.d("disabled: $className")
            }
        }.onFailure { HLog.w("disable failed $className: ${it.message}") }
    }

    // ==================== Dialog ====================

    private fun installDialogs(base: XposedInterface, pkg: String, dialogRules: List<RuleItem>) {
        val parsed = dialogRules.mapNotNull { rule ->
            runCatching {
                com.adhammer.rules.Jsonx.fromJsonArray<com.adhammer.rules.model.DialogRule>(rule.adKey)
            }.getOrNull()?.let { rule to it }
        }
        if (parsed.isEmpty()) return

        base.hookIt(
            Reflect.findMethod(android.app.Dialog::class.java, "show"),
            "wuKong_dialog_show"
        ) { chain ->
            val dialog = chain.getThisObject() as? android.app.Dialog
            if (dialog == null) return@hookIt chain.proceed()
            val name = dialog.javaClass.name
            if (name.startsWith("com.adhammer")) return@hookIt chain.proceed()

            for ((_, list) in parsed) {
                val hit = list.firstOrNull { it.matches(dialog, pkg) } ?: continue
                HLog.d("hit dialog rule at=${hit.at}")
                if (hit.isAutoClick) break   // 自动点击要先 show 再点
                // at=0 禁止 / at=1 取消：吞掉 show
                if (hit.d > 0) {
                    postDialogDelay(dialog, hit.d) { it.dismiss() }
                } else {
                    runCatching { dialog.dismiss() }
                }
                return@hookIt null
            }
            chain.proceed()
        }
    }

    private fun postDialogDelay(dialog: android.app.Dialog, d: Long, block: (android.app.Dialog) -> Unit) {
        mainHandler.postDelayed({ runCatching { block(dialog) } }, d)
    }

    /** 弹框规则匹配：id / 类名 / 文字 任一命中；三者都空则无条件匹配。 */
    private fun com.adhammer.rules.model.DialogRule.matches(dialog: android.app.Dialog, pkg: String): Boolean {
        if (id.isNullOrBlank() && t.isNullOrBlank() && n.isNullOrBlank()) return true
        if (!n.isNullOrBlank() && n == dialog.javaClass.name) return true
        if (!id.isNullOrBlank()) {
            for (one in id.split(";")) {
                val resId = resolveId(dialog.context, one, pkg)
                if (resId != 0 && dialog.findViewById<View>(resId) != null) return true
            }
        }
        if (!t.isNullOrBlank()) {
            val decor = dialog.window?.decorView
            if (decor is ViewGroup) {
                for (one in t.split(";")) {
                    val out = ArrayList<View>()
                    decor.findViewsWithText(out, one, ViewGroup.FIND_VIEWS_WITH_TEXT)
                    if (out.isNotEmpty()) return true
                }
            }
        }
        return false
    }

    private fun resolveId(ctx: android.content.Context, name: String, pkg: String): Int =
        runCatching {
            if (name.contains(":id/")) ctx.resources.getIdentifier(name, "id", null)
            else ctx.resources.getIdentifier(name, "id", pkg)
        }.getOrDefault(0)

    // ==================== 2. View 级（精准匹配隐藏） ====================

    fun installViews(base: XposedInterface, pkg: String, cl: ClassLoader) {
        val packs = RuntimeCfg.packsOf(pkg).filter { it.ruleType == RuleTypes.WU_KONG_VIEW && it.isEnable }
        // Activity 名 → ViewRule 列表
        val viewMap = LinkedHashMap<String, List<com.adhammer.rules.model.ViewRule>>()
        packs.forEach { pack ->
            pack.rules.filter { it.isEnable }.forEach { item ->
                val list = runCatching {
                    com.adhammer.rules.Jsonx.fromJsonArray<com.adhammer.rules.model.ViewRule>(item.adKey)
                }.getOrNull() ?: return@forEach
                if (list.isNotEmpty()) viewMap[item.ad ?: return@forEach] = list
            }
        }
        if (viewMap.isEmpty()) return

        val engine = ViewEngine(viewMap, pkg)

        // 记录当前 Activity，View 规则按 Activity 名索引
        base.hookIt(
            Reflect.findMethod(Activity::class.java, "onCreate", Bundle::class.java),
            "wuKong_view_act_onCreate"
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                (chain.getThisObject() as? Activity)?.let { engine.actName = it.javaClass.name }
            }
            result
        }

        base.hookIt(
            Reflect.findMethod(View::class.java, "setVisibility", Integer.TYPE),
            "wuKong_view_setVisibility"
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                val v = chain.getThisObject() as? View
                if (v != null && chain.getArg(0) as? Int != View.GONE) engine.check(v)
            }
            result
        }

        base.hookIt(
            Reflect.findMethod(TextView::class.java, "setText", CharSequence::class.java),
            "wuKong_view_setText"
        ) { chain ->
            val result = chain.proceed()
            runCatching { (chain.getThisObject() as? View)?.let { engine.check(it) } }
            result
        }

        base.hookIt(
            Reflect.findMethod(View::class.java, "setLayoutParams", ViewGroup.LayoutParams::class.java),
            "wuKong_view_setLayoutParams"
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                if (chain.getArg(0) != null) (chain.getThisObject() as? View)?.let { engine.check(it) }
            }
            result
        }

        // onStart / onResume 时主动扫一遍（覆盖未走 setText 的静态布局）
        val rescan: (XposedInterface.Chain) -> Any? = { chain ->
            val result = chain.proceed()
            runCatching {
                (chain.getThisObject() as? Activity)?.let { act ->
                    act.findViewById<View>(android.R.id.content)?.let { engine.scanTree(it) }
                }
            }
            result
        }
        base.hookIt(Reflect.findMethod(Activity::class.java, "onResume"), "wuKong_act_onResume", rescan)
    }

    // ==================== 3. URL 级 ====================

    fun installUrl(base: XposedInterface, pkg: String, cl: ClassLoader) {
        val urlRules = RuntimeCfg.rulesOf(pkg, RuleTypes.WU_KONG_URL).filter { it.isUrlKey }
        if (urlRules.isEmpty()) return

        // 预编译正则，避免每次请求都编译
        val patterns = urlRules.mapNotNull { r ->
            r.adKey?.takeIf { it.isNotBlank() }?.let { key ->
                runCatching { Regex(key) }.getOrNull()?.let { it to r }
            }
        }
        if (patterns.isEmpty()) return

        val blocked: (String) -> Boolean = { url ->
            patterns.any { (re, _) -> re.containsMatchIn(url) }
        }

        base.hookIt(
            Reflect.findMethod(java.net.URL::class.java, "openConnection"),
            "wuKong_url_openConnection"
        ) { chain ->
            val url = (chain.getThisObject() as? java.net.URL)?.toString()
            if (url != null && blocked(url)) {
                HLog.d("block url: $url")
                HitLog.hit(pkg, url.take(60), "URL拦截", StatsStore.KIND_PAGE)
                return@hookIt null
            }
            chain.proceed()
        }

        val webView = Reflect.findClass("android.webkit.WebView", cl)
        if (webView != null) {
            base.hookIt(Reflect.findMethod(webView, "loadUrl", String::class.java), "wuKong_webview_loadUrl") { chain ->
                val url = chain.getArg(0) as? String
                if (url != null && blocked(url)) {
                    HLog.d("block webview url: $url")
                    HitLog.hit(pkg, url.take(60), "WebView拦截", StatsStore.KIND_PAGE)
                    return@hookIt null
                }
                chain.proceed()
            }
        }
    }

    // ==================== 4. 组件级（四大组件 + Intent） ====================

    fun installComponent(base: XposedInterface, pkg: String, cl: ClassLoader) {
        val rules = RuntimeCfg.rulesOf(pkg, RuleTypes.WU_KONG_COMPONENT)
        if (rules.isEmpty()) return

        val app = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val m = atClass.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            m.invoke(null) as? Application
        }.getOrNull()

        val ctx: android.content.Context? = app ?: curAct?.get()

        if (ctx != null) {
            val pm = ctx.packageManager
            // 组件屏蔽放在主线程稍后执行，避免与 Application 启动竞争
            mainHandler.post {
                var n = 0
                for (r in rules) {
                    val cn = r.ad ?: continue
                    runCatching {
                        when (r.actionType) {
                            Actions.BLOCK_ACTIVITY, Actions.BLOCK_RECEIVER,
                            Actions.BLOCK_SERVICE, Actions.BLOCK_PROVIDER, Actions.DISABLE_ACT -> {
                                if (pm.getComponentEnabledSetting(ComponentName(pkg, cn))
                                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                ) {
                                    pm.setComponentEnabledSetting(
                                        ComponentName(pkg, cn),
                                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                        PackageManager.DONT_KILL_APP
                                    )
                                    n++
                                }
                            }
                        }
                    }
                }
                HLog.i("components disabled: $n/${rules.size}")
            }
        } else {
            HLog.w("no context for component blocking")
        }

        // at=9：Intent 防火墙，在启动阶段拦掉
        val blocked = rules.filter { it.actionType == Actions.BLOCK_INTENT }.mapNotNull { it.ad }.toSet()
        if (blocked.isEmpty()) return

        val intentHook: (XposedInterface.Chain) -> Any? = { chain ->
            var intent: Intent? = null
            for (arg in chain.getArgs()) if (arg is Intent) { intent = arg; break }
            val cn = intent?.component
            val hit = cn != null && cn.className in blocked && intent.data == null
            if (hit) {
                HLog.d("firewall block: ${cn?.className}")
                null
            } else {
                chain.proceed()
            }
        }
        base.hookIt(
            Reflect.findMethod(ContextWrapper::class.java, "startActivity", Intent::class.java),
            "wuKong_ifw_startActivity", intentHook
        )
    }

    // ==================== 5. 文件替换 ====================

    fun installReplace(base: XposedInterface, pkg: String) {
        val rules = RuntimeCfg.rulesOf(pkg, RuleTypes.WU_KONG_REPLACE)
        if (rules.isEmpty()) return
        mainHandler.post {
            for (r in rules) {
                val path = r.adKey?.takeIf { it.isNotBlank() } ?: r.ad ?: continue
                if (!isSafePath(path)) {
                    HLog.w("unsafe replace path, skip: $path")
                    continue
                }
                runCatching {
                    when (r.actionType) {
                        Actions.REPLACE_DIR -> replaceWithEmptyDir(path)
                        Actions.REPLACE_FILE -> replaceWithEmptyFile(path)
                        Actions.REPLACE_DELETE -> deleteRecursively(java.io.File(path))
                        else -> replaceWithEmptyDir(path)
                    }
                }.onFailure { HLog.w("replace failed $path: ${it.message}") }
            }
        }
    }

    /** 安全约束：不得动 /data、/data/data 根与外置存储根 */
    private fun isSafePath(path: String): Boolean {
        val p = path.trimEnd('/')
        if (p.isBlank()) return false
        if (p == "/data" || p == "/data/data") return false
        if (!Regex("(^\\.|^/|^[a-zA-Z])?/.+(/\$)?").containsMatchIn(path)) return false
        return true
    }

    private fun replaceWithEmptyDir(path: String) {
        val f = java.io.File(path)
        if (f.exists()) deleteRecursively(f)
        f.mkdirs()
        HLog.d("replaced with empty dir: $path")
    }

    private fun replaceWithEmptyFile(path: String) {
        val f = java.io.File(path)
        if (f.exists()) deleteRecursively(f)
        runCatching { f.parentFile?.mkdirs() }
        f.createNewFile()
        HLog.d("replaced with empty file: $path")
    }

    private fun deleteRecursively(f: java.io.File) {
        if (!f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        runCatching { f.delete() }
    }
}
