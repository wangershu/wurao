package com.wurao.core.selector

/**
 * 选择器解析器：手写词法 + 递归下降语法。
 *
 * 原则：**不允许静默失败** —— 解析不出来必须带上出错位置抛出来（项目硬约束），
 * 调用方负责把错误显示给用户（"这条规则为什么没生效"的一半答案就在这里）。
 */
class SelectorSyntaxError(val pos: Int, message: String) :
    RuntimeException("选择器语法错误 @$pos：$message")

class SelectorParser(private val src: String) {

    private var p = 0

    fun parse(): Selector {
        val units = mutableListOf<Unit>()
        while (true) {
            units.add(parseUnit())
            skipWs()
            if (p >= src.length) break

            val rel = RelationOp.parse(src, p)
                ?: throw SelectorSyntaxError(p, "这里应该是关系操作符（+ - > < << ->）或结束，实际是 '${peek()}'")
            p += rel.second
            skipWs()

            val offset = readIntHere()
            // 把关系写回上一段（关系属于「这一段到下一段」）
            units[units.lastIndex] = units.last().copy(
                relation = rel.first,
                offset = offset ?: 1,
            )
            skipWs()
            if (p >= src.length) throw SelectorSyntaxError(p, "关系 '${rel.first.sym}' 后面缺少下一段")
        }
        if (units.isEmpty()) throw SelectorSyntaxError(0, "空选择器")
        return Selector(units)
    }

    // ---------------- 段 ----------------

    private fun parseUnit(): Unit {
        skipWs()
        var isTarget = false
        if (peek() == '@') {
            p++
            isTarget = true
            skipWs()
        }

        var className: String? = null
        when {
            peek() == '*' -> {
                p++
                className = "*"
            }

            isIdentStart(peek()) -> className = readIdent()
        }

        val props = mutableListOf<BoolExpr>()
        while (true) {
            skipWs()
            if (peek() != '[') break
            p++
            props.add(parseExpr())
            skipWs()
            if (peek() != ']') throw SelectorSyntaxError(p, "属性选择器缺少 ']'")
            p++
        }

        val expr = when {
            props.isEmpty() -> BoolExpr.Always
            props.size == 1 -> props[0]
            else -> BoolExpr.And(props)
        }
        return Unit(className, expr, isTarget, null, 1)
    }

    // ---------------- 逻辑表达式 ----------------

    private fun parseExpr(): BoolExpr = parseOr()

    private fun parseOr(): BoolExpr {
        val parts = mutableListOf(parseAnd())
        while (true) {
            skipWs()
            if (src.startsWith("||", p)) {
                p += 2
                parts.add(parseAnd())
            } else break
        }
        return if (parts.size == 1) parts[0] else BoolExpr.Or(parts)
    }

    private fun parseAnd(): BoolExpr {
        val parts = mutableListOf(parseUnary())
        while (true) {
            skipWs()
            if (src.startsWith("&&", p)) {
                p += 2
                parts.add(parseUnary())
            } else break
        }
        return if (parts.size == 1) parts[0] else BoolExpr.And(parts)
    }

    private fun parseUnary(): BoolExpr {
        skipWs()
        if (peek() == '!' && src.getOrNull(p + 1) == '(') {
            p += 1 // 跳过 '!'，括号由下面处理
            return BoolExpr.Not(parsePrimary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): BoolExpr {
        skipWs()
        if (peek() == '(') {
            p++
            val e = parseExpr()
            skipWs()
            if (peek() != ')') throw SelectorSyntaxError(p, "缺少 ')'")
            p++
            return e
        }
        return parseCmp()
    }

    private fun parseCmp(): BoolExpr {
        skipWs()
        val left = readRef() ?: throw SelectorSyntaxError(p, "期望属性名（如 text / vid / text.length）")
        skipWs()
        val (op, len) = CompareOp.parse(src, p)
            ?: throw SelectorSyntaxError(p, "属性 '$left' 后面期望比较运算符（= != ^= *= $= < <= > >= ~=）")
        p += len
        skipWs()
        val right = readValue()
            ?: throw SelectorSyntaxError(p, "'$left ${op.sym}' 后面缺少比较值")
        return BoolExpr.Cmp(left, op, right)
    }

    // ---------------- 词法 ----------------

    private fun peek(): Char = src.getOrNull(p) ?: '\u0000'

    private fun skipWs() {
        while (p < src.length && src[p].isWhitespace()) p++
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentPart(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '.' || c == '$' || c == ':'

    /** 读一个标识符（类名或属性路径），返回原始串。 */
    private fun readIdent(): String {
        val start = p
        while (p < src.length) {
            val c = src[p]
            // `$` 既可能是类名里的内部类分隔符（Outer$Inner），也可能是 `$=` 运算符的开头。
            // 只有紧跟 `=` 时才把它让给运算符，否则仍算标识符的一部分。
            if (c == '$' && src.getOrNull(p + 1) == '=') break
            if (!isIdentPart(c)) break
            p++
        }
        if (p == start) throw SelectorSyntaxError(p, "期望标识符")
        return src.substring(start, p)
    }

    /** 读属性引用（左侧），转成路径列表。 */
    private fun readRef(): Value.Ref? {
        if (!isIdentStart(peek())) return null
        val raw = readIdent()
        return Value.Ref(raw.split('.'))
    }

    private fun readValue(): Value? {
        when (peek()) {
            '"', '\'' -> {
                val quote = src[p]
                p++
                val sb = StringBuilder()
                while (p < src.length && src[p] != quote) {
                    if (src[p] == '\\' && p + 1 < src.length) {
                        sb.append(src[p + 1])
                        p += 2
                    } else {
                        sb.append(src[p])
                        p++
                    }
                }
                if (p >= src.length) throw SelectorSyntaxError(p, "字符串没有闭合的 $quote")
                p++
                return Value.Str(sb.toString())
            }

            '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                val n = readIntHere()
                    ?: throw SelectorSyntaxError(p, "数字格式不对")
                return Value.Num(n)
            }
        }
        if (isIdentStart(peek())) {
            val raw = readIdent()
            return when (raw) {
                "true" -> Value.Bool(true)
                "false" -> Value.Bool(false)
                else -> Value.Ref(raw.split('.'))
            }
        }
        return null
    }

    /** 在当前位置读一个整数（用于 offset 与数字字面量），读不到返回 null。 */
    private fun readIntHere(): Int? {
        val start = p
        var i = p
        if (src.getOrNull(i) == '-') i++
        val digitsStart = i
        while (i < src.length && src[i].isDigit()) i++
        if (i == digitsStart) return null
        val v = src.substring(start, i).toIntOrNull() ?: return null
        p = i
        return v
    }
}
