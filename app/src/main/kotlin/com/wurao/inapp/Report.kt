package com.wurao.inapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.wurao.core.HitContract
import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import com.wurao.core.model.Rule
import java.lang.ref.WeakReference

/**
 * 把命中记录**从目标进程送回界面**（排错用）。
 *
 * 通道是模块暴露的 ContentProvider：目标进程 insert，界面 query。
 * 选它不选广播的原因：广播是"推"，界面没打开就收不到；
 * ContentProvider 先把记录存下来，你什么时候打开界面都能看到。
 *
 * 三条规矩：
 * 1. 拿不到 Context 就静默跳过（**排错通道失效不能影响去广告本身**）；
 * 2. 所有异常吞掉并记一笔，绝不能让目标 App 崩；
 * 3. 只在真的动手之后才上报，没点成功的不能算。
 */
object Report {

    private var ctxRef: WeakReference<Context>? = null

    fun attach(ctx: Context) {
        ctxRef = WeakReference(ctx.applicationContext)
    }

    fun hit(rule: Rule, pkg: String, page: String?) {
        push(
            pkg = pkg,
            ruleId = rule.id,
            note = rule.note.ifBlank { rule.act.key },
            act = rule.act.key,
            domain = rule.domain,
            target = rule.target,
            page = page,
        )
    }

    /** 没有规则身份的动作（DNS / 组件 / SDK 拦截）。 */
    fun hitRaw(
        pkg: String,
        key: String,
        note: String,
        act: Act,
        domain: Domain,
        target: String,
    ) {
        push(pkg, key, note, act.key, domain, target, null)
    }

    private fun push(
        pkg: String,
        ruleId: String,
        note: String,
        act: String,
        domain: Domain,
        target: String,
        page: String?,
    ) {
        val ctx = ctxRef?.get() ?: return
        runCatching {
            val v = ContentValues().apply {
                put(HitContract.COL_PKG, pkg)
                put(HitContract.COL_RULE_ID, ruleId)
                put(HitContract.COL_NOTE, note)
                put(HitContract.COL_ACT, act)
                put(HitContract.COL_TARGET, target)
                put(HitContract.COL_PAGE, page)
                put(HitContract.COL_COUNT, 1)
                put(HitContract.COL_LAST_AT, System.currentTimeMillis())
            }
            ctx.contentResolver.insert(Uri.parse(HitContract.URI_STRING), v)
        }.onFailure {
            Log.w("命中回传失败（不影响去广告）: ${it.javaClass.simpleName}")
        }
        if (domain == Domain.HOST) Log.d("上报命中: $pkg $note")
    }
}
