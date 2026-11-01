package com.wurao.ui

/**
 * 命中记录存储（**模块进程侧**）。
 *
 * 为什么单独一份：命中发生在**目标 App 进程**里，界面在模块进程，两边不共享内存。
 * 目标进程通过 [HitProvider] 把记录写过来，界面从这里读 —— 这样排错时才能看到
 * "这条规则干过什么"，并直接跳到它去停用/删除。
 *
 * 存储格式刻意用最简单的行式文本（字段用 \t 分隔），不加 JSON 依赖：
 * 记录量很小（按规则聚合），够用且好查。
 */
object HitStore {

    data class Row(
        val pkg: String,
        val ruleId: String,
        val note: String,
        val act: String,
        val target: String,
        val page: String?,
        val count: Int,
        val lastAtMs: Long,
    ) {
        fun describe(): String = buildString {
            append(note.ifBlank { act })
            append(" · ").append(act)
            if (count > 1) append(" ×").append(count)
            page?.let { append(" @ ").append(it.substringAfterLast('.')) }
        }
    }

    private val rows = LinkedHashMap<String, Row>()

    private fun key(pkg: String, ruleId: String) = "$pkg|$ruleId"

    /** 合并一条（同一条规则只涨次数与时间，不新增行）。 */
    fun merge(r: Row) {
        val k = key(r.pkg, r.ruleId)
        val old = rows[k]
        rows[k] = if (old == null) r else r.copy(count = old.count + r.count, lastAtMs = maxOf(old.lastAtMs, r.lastAtMs))
    }

    fun all(): List<Row> = rows.values.sortedByDescending { it.lastAtMs }

    fun forApp(pkg: String): List<Row> = all().filter { it.pkg == pkg }

    fun clear(pkg: String? = null) {
        if (pkg == null) rows.clear()
        else rows.entries.removeIf { it.value.pkg == pkg }
    }

    fun replaceAll(list: List<Row>) {
        rows.clear()
        list.forEach { rows[key(it.pkg, it.ruleId)] = it }
    }

    // ---------------- 序列化（纯函数，可单测） ----------------

    private fun esc(s: String): String = s.replace("\t", " ").replace("\n", " ")

    fun encode(list: List<Row>): String = list.joinToString("\n") { r ->
        listOf(
            esc(r.pkg), esc(r.ruleId), esc(r.note), esc(r.act),
            esc(r.target), esc(r.page ?: ""), r.count.toString(), r.lastAtMs.toString(),
        ).joinToString("\t")
    }

    fun decode(text: String): List<Row> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 8) return@mapNotNull null
        Row(
            pkg = p[0],
            ruleId = p[1],
            note = p[2],
            act = p[3],
            target = p[4],
            page = p[5].takeIf { it.isNotEmpty() },
            count = p[6].toIntOrNull() ?: 1,
            lastAtMs = p[7].toLongOrNull() ?: 0L,
        )
    }.toList()
}
