package com.wurao.core.selector

/**
 * 选择器求值。
 *
 * 两个必须守住的点（都是踩过才知道的）：
 * 1. **树遍历用显式栈** —— 递归写法在深树（WebView / 长列表）上会爆 JVM 栈；
 * 2. **失败记忆化** —— 同一个节点在同一段上失败过就不再算第二次，
 *    否则深选择器的开销是指数级的。
 *
 * 暂不支持 `->`（回到之前节点）：它需要"上一次匹配到哪"的全局游标，
 * 用到时再补；现在遇到会直接报出来，不静默当成没匹配。
 */
class SelectorMatcher<T>(private val adapter: NodeAdapter<T>) {

    /** 失败记忆化：(段下标, 节点 key)。每次 match 清空。 */
    private val failed = HashSet<Pair<Int, Any>>()

    /** 各段当前命中的节点，用于取 `@` 标记的那一段。 */
    private lateinit var path: Array<Any?>

    /**
     * 在 [root] 为根的树里找第一个匹配，返回**动作目标节点**（`@` 标记的那段；
     * 没标记就是最后一段）。找不到返回 null。
     */
    fun match(selector: Selector, root: T): T? {
        failed.clear()
        path = arrayOfNulls(selector.units.size)
        val units = selector.units

        // 显式栈 DFS：以每个节点为第一段起点尝试一次
        val stack = ArrayList<T>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            if (tryFrom(node, 0, units)) {
                @Suppress("UNCHECKED_CAST")
                return path[selector.targetIndex] as? T
            }
            val n = adapter.childCount(node)
            for (i in n - 1 downTo 0) adapter.child(node, i)?.let(stack::add)
        }
        return null
    }

    /** 从 [node] 开始匹配第 [idx] 段及其后续。 */
    private fun tryFrom(node: T, idx: Int, units: List<Unit>): Boolean {
        val cacheKey = idx to adapter.key(node)
        if (cacheKey in failed) return false

        val unit = units[idx]
        if (!matchUnit(node, unit)) {
            failed.add(cacheKey)
            return false
        }
        path[idx] = node

        if (idx == units.lastIndex) return true

        val next = candidates(node, unit) ?: run {
            failed.add(cacheKey)
            return false
        }
        // offset 的语义是「第 n 个**匹配**的候选」，不是「第 n 个候选」
        var hit = 0
        for (cand in next) {
            if (tryFrom(cand, idx + 1, units)) {
                hit++
                if (hit >= unit.offset) return true
            }
        }
        failed.add(cacheKey)
        return false
    }

    /** 单段是否匹配（类名 + 属性表达式）。 */
    private fun matchUnit(node: T, unit: Unit): Boolean {
        val cls = unit.className
        if (cls != null && cls != "*" && !classNameMatches(node, cls)) return false
        return eval(unit.expr, node)
    }

    /**
     * 类名匹配三种写法都要支持：
     * - 全限定全等：`android.widget.TextView`（GKD 订阅里的标准写法）
     * - 前缀：`android.widget.` 这种（原版就靠前缀定位）
     * - **简写**：选择器写 `TextView` 也能匹配 `android.widget.TextView`
     *   （手写规则时没人愿意打全限定名；只在 expect 不含 `.` 时启用，不影响订阅里的全限定写法）
     */
    private fun classNameMatches(node: T, expect: String): Boolean {
        val actual = adapter.className(node) ?: return false
        if (actual == expect) return true
        if (actual.startsWith(expect)) return true
        if (expect.indexOf('.') < 0 && actual.substringAfterLast('.') == expect) return true
        return false
    }

    /** 按关系取下一段的候选节点。 */
    private fun candidates(node: T, unit: Unit): List<T>? {
        val rel = unit.relation ?: return null
        val out = ArrayList<T>()
        when (rel) {
            RelationOp.DESCENDANT -> descendants(node, out)     // <<
            RelationOp.CHILD -> {                                // <
                val n = adapter.childCount(node)
                for (i in 0 until n) adapter.child(node, i)?.let(out::add)
            }

            RelationOp.ANCESTOR -> {                             // >
                var cur = adapter.parent(node)
                while (cur != null) {
                    out.add(cur)
                    cur = adapter.parent(cur)
                }
            }

            RelationOp.PREV_SIBLING, RelationOp.NEXT_SIBLING -> { // + -
                val p = adapter.parent(node) ?: return emptyList()
                val n = adapter.childCount(p)
                val self = (0 until n).firstOrNull { adapter.child(p, it)?.let { c -> adapter.key(c) } == adapter.key(node) }
                    ?: return emptyList()
                if (rel == RelationOp.PREV_SIBLING) {
                    for (i in self - 1 downTo 0) adapter.child(p, i)?.let(out::add)
                } else {
                    for (i in self + 1 until n) adapter.child(p, i)?.let(out::add)
                }
            }

            RelationOp.PREV ->
                throw UnsupportedOperationException("暂不支持 '->' 关系（需要匹配游标），用到时再实现")
        }
        return out
    }

    /** 所有后代（深度先序，显式栈）。 */
    private fun descendants(node: T, out: MutableList<T>) {
        val stack = ArrayList<T>()
        val n = adapter.childCount(node)
        for (i in n - 1 downTo 0) adapter.child(node, i)?.let(stack::add)
        while (stack.isNotEmpty()) {
            val cur = stack.removeAt(stack.lastIndex)
            out.add(cur)
            val cn = adapter.childCount(cur)
            for (i in cn - 1 downTo 0) adapter.child(cur, i)?.let(stack::add)
        }
    }

    // ---------------- 表达式求值 ----------------

    private fun eval(e: BoolExpr, node: T): Boolean = when (e) {
        BoolExpr.Always -> true
        is BoolExpr.And -> e.parts.all { eval(it, node) }
        is BoolExpr.Or -> e.parts.any { eval(it, node) }
        is BoolExpr.Not -> !eval(e.e, node)
        is BoolExpr.Cmp -> cmp(e, node)
    }

    private fun cmp(c: BoolExpr.Cmp, node: T): Boolean {
        val left = adapter.attr(node, c.left.path)
        val right = when (val r = c.right) {
            is Value.Ref -> adapter.attr(node, r.path)
            is Value.Str -> r.v
            is Value.Num -> r.v
            is Value.Bool -> r.v
        }
        // null 传播：属性拿不到时，=` 不成立、`!=` 成立，其余一律不成立
        if (left == null || right == null) {
            val sameNull = left == null && right == null
            return when (c.op) {
                CompareOp.EQ -> sameNull
                CompareOp.NE -> !sameNull
                else -> false
            }
        }
        return when (c.op) {
            CompareOp.EQ -> left == right
            CompareOp.NE -> left != right
            CompareOp.STARTS -> str(left).startsWith(str(right))
            CompareOp.NOT_STARTS -> !str(left).startsWith(str(right))
            CompareOp.CONTAINS -> str(left).contains(str(right))
            CompareOp.NOT_CONTAINS -> !str(left).contains(str(right))
            CompareOp.ENDS -> str(left).endsWith(str(right))
            CompareOp.NOT_ENDS -> !str(left).endsWith(str(right))
            CompareOp.LT -> num(left) < num(right)
            CompareOp.LE -> num(left) <= num(right)
            CompareOp.GT -> num(left) > num(right)
            CompareOp.GE -> num(left) >= num(right)
            CompareOp.MATCHES -> Regex(str(right)).containsMatchIn(str(left))
            CompareOp.NOT_MATCHES -> !Regex(str(right)).containsMatchIn(str(left))
        }
    }

    private fun str(v: Any?): String = v?.toString() ?: ""

    private fun num(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        else -> v?.toString()?.toIntOrNull() ?: 0
    }
}
