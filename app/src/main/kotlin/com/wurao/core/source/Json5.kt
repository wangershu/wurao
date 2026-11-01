package com.wurao.core.source

/**
 * 极简 JSON5 解析。
 *
 * 为什么自己写：GKD 订阅是 JSON5 —— key 不加引号、字符串用单引号、数组末尾允许逗号、
 * 还可能有注释，标准 JSON 解析器一口都吃不下（旧实现为这个专门写过一层）。
 * 只支持订阅里真正会出现的语法，不做通用 JSON5。
 *
 * 解析结果用 Map / List / String / Long / Double / Boolean / null 表示。
 */
internal object Json5 {

    class Json5Error(val pos: Int, message: String) :
        RuntimeException("JSON5 解析错误 @$pos：$message")

    fun parseObject(text: String): Map<String, Any?> {
        val v = Parser(text).parseValue()
        return v as? Map<String, Any?> ?: throw Json5Error(0, "顶层不是对象")
    }

    private class Parser(private val s: String) {
        private var p = 0

        fun parseValue(): Any? {
            skipWs()
            when (peek()) {
                '{' -> return parseObject()
                '[' -> return parseArray()
                '\'' -> return parseString('\'')
                '"' -> return parseString('"')
                '-', '+', '.' -> return parseNumber()
            }
            if (peek().isDigit()) return parseNumber()
            return parseBare()
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                p++
                return out
            }
            while (true) {
                skipWs()
                val key = when (peek()) {
                    '\'' -> parseString('\'')
                    '"' -> parseString('"')
                    else -> readBareKey()
                }
                skipWs()
                if (peek() != ':') throw Json5Error(p, "对象里缺少 ':'（key=$key）")
                p++
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        p++
                        skipWs()
                        // JSON5 允许尾逗号
                        if (peek() == '}') {
                            p++
                            return out
                        }
                    }

                    '}' -> {
                        p++
                        return out
                    }

                    else -> throw Json5Error(p, "对象里期望 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                p++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> {
                        p++
                        skipWs()
                        if (peek() == ']') {
                            p++
                            return out
                        }
                    }

                    ']' -> {
                        p++
                        return out
                    }

                    else -> throw Json5Error(p, "数组里期望 ',' 或 ']'")
                }
            }
        }

        private fun parseString(quote: Char): String {
            expect(quote)
            val sb = StringBuilder()
            while (p < s.length) {
                val c = s[p]
                when {
                    c == '\\' && p + 1 < s.length -> {
                        val n = s[p + 1]
                        sb.append(
                            when (n) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                '\\' -> '\\'
                                '\'' -> '\''
                                '"' -> '"'
                                else -> n
                            }
                        )
                        p += 2
                    }

                    c == quote -> {
                        p++
                        return sb.toString()
                    }

                    else -> {
                        sb.append(c)
                        p++
                    }
                }
            }
            throw Json5Error(p, "字符串没有闭合的 $quote")
        }

        private fun parseNumber(): Any? {
            val start = p
            if (peek() == '-' || peek() == '+') p++
            while (p < s.length && (s[p].isDigit() || s[p] == '.' || s[p] == 'e' || s[p] == 'E' ||
                        (s[p] == '-' && s[p - 1] in "eE"))
            ) p++
            val raw = s.substring(start, p)
            val asLong = raw.toLongOrNull()
            return asLong ?: raw.toDoubleOrNull() ?: throw Json5Error(start, "数字格式不对: $raw")
        }

        /** 无引号的字面量：true / false / null，或（JSON5 允许）无引号 key 的值 */
        private fun parseBare(): Any? {
            val start = p
            while (p < s.length && s[p] !in ",]}:") p++
            val raw = s.substring(start, p).trim()
            return when (raw) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> throw Json5Error(start, "无法识别的字面量: $raw")
            }
        }

        private fun readBareKey(): String {
            val start = p
            while (p < s.length && s[p] != ':' && s[p] != ',' && s[p] != '}' && !s[p].isWhitespace()) p++
            return s.substring(start, p).trim().also {
                if (it.isEmpty()) throw Json5Error(start, "空的 key")
            }
        }

        private fun skipWs() {
            while (p < s.length) {
                when {
                    s[p].isWhitespace() -> p++
                    // 注释：// 行注释 与 /* */ 块注释
                    s[p] == '/' && s.getOrNull(p + 1) == '/' -> {
                        while (p < s.length && s[p] != '\n') p++
                    }

                    s[p] == '/' && s.getOrNull(p + 1) == '*' -> {
                        p += 2
                        while (p + 1 < s.length && !(s[p] == '*' && s[p + 1] == '/')) p++
                        p += 2
                    }

                    else -> return
                }
            }
        }

        private fun peek(): Char = s.getOrNull(p) ?: '\u0000'

        private fun expect(c: Char) {
            if (peek() != c) throw Json5Error(p, "期望 '$c'")
            p++
        }
    }
}
