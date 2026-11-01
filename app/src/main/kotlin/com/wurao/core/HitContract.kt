package com.wurao.core

/**
 * 命中记录回传的契约（**hook 侧与界面侧共用**）：目标进程写、界面读。
 *
 * 放在 core 是为了让 inapp（hook）不必依赖 ui（界面）包。
 */
object HitContract {

    const val AUTHORITY = "com.wurao.app.hits"
    const val URI_STRING = "content://$AUTHORITY/hits"

    const val COL_PKG = "pkg"
    const val COL_RULE_ID = "rule_id"
    const val COL_NOTE = "note"
    const val COL_ACT = "act"
    const val COL_TARGET = "target"
    const val COL_PAGE = "page"
    const val COL_COUNT = "count"
    const val COL_LAST_AT = "last_at"
}
