package com.wurao.core.selector

/**
 * 选择器语法树（对齐 GKD 选择器语言的子集）。
 *
 * 为什么要自己写选择器而不是直接用结构化字段：原版那套「类名+id+文字+往上几层」
 * 表达不了兄弟关系、属性长度、逻辑组合，而这些正是实际规则里最常见的判据
 * （例：GKD 用 `[text.length<10]` 挡掉「跳过广告可获赠会员」这种营销按钮）。
 *
 * 关键设计（照 GKD，不抄代码）：
 * - 一个选择器由若干 **段（Unit）** 组成，段之间用关系操作符连接；
 * - **`@` 标记哪一段是「要动手的目标」**，没标记则默认最后一段 ——
 *   这个语法直接支撑「找到一个控件，但动它的父容器」这种需求
 *   （例：找到写「视频」的标签，藏掉它所在的整个入口容器）。
 */

/** 比较运算符。只有 `= ^= *= $=` 能生成快速查询候选集，其余走全量求值。 */
enum class CompareOp(val sym: String) {
    EQ("="), NE("!="),
    STARTS("^="), NOT_STARTS("!^="),
    CONTAINS("*="), NOT_CONTAINS("!*="),
    ENDS("$="), NOT_ENDS("!$="),
    LT("<"), LE("<="), GT(">"), GE(">="),
    MATCHES("~="), NOT_MATCHES("!~="),
    ;

    /** 能否用它直接在树里定位候选（不必遍历全树）。 */
    val isFastQuery: Boolean get() = this == EQ || this == STARTS || this == CONTAINS || this == ENDS

    companion object {
        /** 长按符号优先匹配（先试 `!=` 再试 `=`，否则会把 `!=` 认成 `=`）。 */
        fun parse(s: String, at: Int): Pair<CompareOp, Int>? {
            for (len in 2 downTo 1) {
                val sym = s.substring(at, (at + len).coerceAtMost(s.length))
                val op = entries.firstOrNull { it.sym == sym }
                if (op != null) return op to len
            }
            return null
        }
    }
}

/** 段与段之间的关系。`offset` 支持 `<<3` / `+2` 这种写法。 */
enum class RelationOp(val sym: String) {
    PREV_SIBLING("+"),
    NEXT_SIBLING("-"),
    ANCESTOR(">"),
    CHILD("<"),
    DESCENDANT("<<"),
    PREV("->"),
    ;

    companion object {
        fun parse(s: String, at: Int): Pair<RelationOp, Int>? {
            // `<<` 要在 `<` 之前试，同理 `->`
            for (sym in listOf("<<", "->", "+", "-", ">", "<")) {
                if (s.startsWith(sym, at)) {
                    return entries.first { it.sym == sym } to sym.length
                }
            }
            return null
        }
    }
}

/** 比较右侧的字面量或变量引用。 */
sealed interface Value {
    data class Str(val v: String) : Value
    data class Num(val v: Int) : Value
    data class Bool(val v: Boolean) : Value
    /** 属性引用，如 `text`、`text.length`、`childCount`。 */
    data class Ref(val path: List<String>) : Value
}

/** 属性条件表达式。 */
sealed interface BoolExpr {
    /** 无条件（该段只有类名）。 */
    data object Always : BoolExpr

    data class Cmp(val left: Value.Ref, val op: CompareOp, val right: Value) : BoolExpr
    data class Not(val e: BoolExpr) : BoolExpr
    data class And(val parts: List<BoolExpr>) : BoolExpr
    data class Or(val parts: List<BoolExpr>) : BoolExpr
}

/**
 * 一段选择器：类名（可空）+ 属性条件 + 与下一段的关系。
 */
data class Unit(
    /** 类名全名或前缀；`*` 通配；null 表示不限类名。 */
    val className: String?,
    val expr: BoolExpr,
    /** 是否被 `@` 标记为动作目标。 */
    val isTarget: Boolean,
    /** 与**下一段**的关系；最后一段为 null。 */
    val relation: RelationOp?,
    /** 关系的偏移/层数，如 `<<3` 的 3、`+2` 的 2。默认 1。 */
    val offset: Int = 1,
)

data class Selector(val units: List<Unit>) {
    /** 动作作用在哪一段：被 `@` 标记的那段，没标记就是最后一段。 */
    val targetIndex: Int
        get() = units.indexOfFirst { it.isTarget }.let { if (it >= 0) it else units.lastIndex }

    /** 规范化回写（用于日志、规则去重、快照比对）。 */
    override fun toString(): String = buildString {
        units.forEachIndexed { i, u ->
            if (i > 0) append(' ')
            if (u.isTarget) append('@')
            append(u.className ?: "*")
            if (u.expr !is BoolExpr.Always) append(exprToString(u.expr))
            if (u.relation != null) {
                append(' ').append(u.relation.sym)
                if (u.offset != 1) append(u.offset)
                append(' ')
            }
        }
    }.trim()

    private fun exprToString(e: BoolExpr): String = when (e) {
        BoolExpr.Always -> ""
        is BoolExpr.Cmp -> "[" + e.left.path.joinToString(".") + e.op.sym + valueToString(e.right) + "]"
        is BoolExpr.Not -> "!(" + exprToString(e.e) + ")"
        is BoolExpr.And -> e.parts.joinToString("") { exprToString(it) }
        is BoolExpr.Or -> "(" + e.parts.joinToString("||") { exprToString(it) } + ")"
    }

    private fun valueToString(v: Value): String = when (v) {
        is Value.Str -> "\"" + v.v + "\""
        is Value.Num -> v.v.toString()
        is Value.Bool -> v.v.toString()
        is Value.Ref -> v.path.joinToString(".")
    }
}
