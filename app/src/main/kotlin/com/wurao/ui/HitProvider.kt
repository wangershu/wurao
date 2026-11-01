package com.wurao.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder

/**
 * 命中记录的**反向通道**：目标进程把命中写进来，界面从这里读。
 *
 * 为什么用 ContentProvider 而不是广播：广播是"推"，只有界面活着时才收得到；
 * ContentProvider 是"拉"，记录先存下来，你什么时候打开界面都能看到。
 *
 * exported=true 是必须的（要让别的进程写），但没有加权限保护 ——
 * 这是自用模块的取舍：换来的是任何被 hook 的进程都能回传记录。
 */
class HitProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.wurao.app.hits"
        val URI: Uri = Uri.parse("content://$AUTHORITY/hits")

        const val COL_PKG = "pkg"
        const val COL_RULE_ID = "rule_id"
        const val COL_NOTE = "note"
        const val COL_ACT = "act"
        const val COL_TARGET = "target"
        const val COL_PAGE = "page"
        const val COL_COUNT = "count"
        const val COL_LAST_AT = "last_at"

        private const val CODE_HITS = 1

        private const val PREF = "wurao_hits"
        private const val KEY = "rows"

        /** 界面启动时把落盘的记录读回内存。 */
        fun restore(ctx: android.content.Context) {
            val text = ctx.getSharedPreferences(PREF, 0).getString(KEY, null) ?: return
            HitStore.replaceAll(HitStore.decode(text))
        }
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val v = values ?: return null
        val row = HitStore.Row(
            pkg = v.getAsString(COL_PKG) ?: return null,
            ruleId = v.getAsString(COL_RULE_ID) ?: return null,
            note = v.getAsString(COL_NOTE) ?: "",
            act = v.getAsString(COL_ACT) ?: "",
            target = v.getAsString(COL_TARGET) ?: "",
            page = v.getAsString(COL_PAGE),
            count = v.getAsInteger(COL_COUNT) ?: 1,
            lastAtMs = v.getAsLong(COL_LAST_AT) ?: System.currentTimeMillis(),
        )
        HitStore.merge(row)
        persist()
        return uri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val rows = HitStore.all()
        val cursor = MatrixCursor(
            arrayOf(
                COL_PKG, COL_RULE_ID, COL_NOTE, COL_ACT,
                COL_TARGET, COL_PAGE, COL_COUNT, COL_LAST_AT,
            )
        )
        rows.forEach { r ->
            cursor.addRow(
                arrayOf(
                    r.pkg, r.ruleId, r.note, r.act, r.target, r.page, r.count, r.lastAtMs,
                )
            )
        }
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        HitStore.clear()
        persist()
        return 1
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.wurao.hit"

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    // ---------------- 持久化（落模块进程的 SharedPreferences） ----------------

    private fun prefs() = context?.getSharedPreferences(PREF, 0)

    private fun persist() {
        // 从别的应用进程写进来时，清掉调用方身份，否则访问模块自己的偏好会被拒
        val token = Binder.clearCallingIdentity()
        try {
            prefs()?.edit()?.putString(KEY, HitStore.encode(HitStore.all()))?.apply()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

}
