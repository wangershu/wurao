package com.wurao.core.net

/**
 * 广告域名黑名单。
 *
 * **它跑在每一个被 hook 的应用进程里**，所以内存与查询代价都要算清楚 ——
 * 旧实现实测过一组数据（域名平均 17 字符）：
 *
 * | 表 | 条数 | HashSet<String> | 本类（反转+扁平+二分） |
 * |---|---:|---:|---:|
 * | AWAvenue | 969 | 0.1 MB | 0.02 MB |
 * | AdClose.txt | 17,341 | **1.9 MB** | 0.3 MB |
 * | close.txt | 127,462 | **14.9 MB** | 2.6 MB |
 *
 * 直接用 `Set<String>` 接这种表，每个进程 1.9MB 起步，十个应用同时跑就是 19MB；
 * 更大的表会到 149MB。而这种问题单机测一个应用时完全看不出来。
 *
 * ### 两个关键点
 *
 * 1. **域名反转**：要的是后缀匹配（`ad.example.com` 命中 `example.com`）。
 *    反转之后后缀匹配变成**前缀**匹配（`moc.elpmaxe` 是 `moc.elpmaxe.da` 的前缀），
 *    排序后二分一次就能定，`O(log n)` 且只查一次 —— 不用逐级剥着试好几回。
 * 2. **扁平存储**：不用 `Array<String>`（每个 String 对象 40+ 字节的头），
 *    而是一个大 [CharArray] + 一个 [IntArray] 偏移表，省掉对象开销。
 *
 * ### 误伤防线
 *
 * 前缀必须是**域名级**：`example.com` 能匹配 `ad.example.com`，但**不能**匹配
 * `notexample.com`（那只是字符串前缀，不是子域名）。判据是前缀之后紧跟 `.`。
 * 换成二分之后必须显式查这一条，否则会把无辜域名也拦掉。
 */
class DomainSet internal constructor(
    private val data: CharArray,
    private val offset: IntArray,
) {

    val size: Int get() = offset.size - 1

    /** 估算占用字节数（给设置页显示用）。 */
    val bytes: Int get() = data.size * 2 + offset.size * 4

    /** 这个域名在不在表里（含子域名匹配）。 */
    fun matches(host: String?): Boolean {
        if (size == 0 || host.isNullOrBlank()) return false
        val q = reverse(host) ?: return false
        var lo = 0
        var hi = size - 1
        var cand = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, q)
            when {
                c == 0 -> return true
                c < 0 -> {
                    cand = mid
                    lo = mid + 1
                }

                else -> hi = mid - 1
            }
        }
        if (cand < 0) return false
        return isDomainPrefix(cand, q)
    }

    private fun reverse(host: String): String? {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return null
        return buildString(h.length) {
            for (i in h.indices.reversed()) append(h[i])
        }
    }

    /** 第 i 个条目与 q 的字典序比较。 */
    private fun compareAt(i: Int, q: String): Int {
        val st = offset[i]
        val len = offset[i + 1] - st
        val n = minOf(len, q.length)
        for (k in 0 until n) {
            val d = data[st + k].compareTo(q[k])
            if (d != 0) return d
        }
        return len - q.length
    }

    /** 第 i 个条目是不是 q 的「域名级」前缀（后面必须紧跟 `.`）。 */
    private fun isDomainPrefix(i: Int, q: String): Boolean {
        val st = offset[i]
        val len = offset[i + 1] - st
        if (len > q.length) return false
        for (k in 0 until len) if (data[st + k] != q[k]) return false
        if (len == q.length) return true
        return q[len] == '.'
    }

    companion object {
        val EMPTY = DomainSet(CharArray(0), intArrayOf(0))

        /**
         * 构建。入参大小写、顺序、带不带尾点都行。
         * 顺手吃掉被更短条目覆盖的：有 `example.com` 就不需要 `ad.example.com`。
         */
        fun of(hosts: Collection<String>): DomainSet {
            if (hosts.isEmpty()) return EMPTY
            val rev = HashSet<String>(hosts.size)
            for (h in hosts) {
                val t = h.trim().trimEnd('.').lowercase()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue
                rev += buildString(t.length) {
                    for (i in t.indices.reversed()) append(t[i])
                }
            }
            if (rev.isEmpty()) return EMPTY
            val sorted = rev.toTypedArray()
            sorted.sort()

            val kept = ArrayList<String>(sorted.size)
            for (s in sorted) {
                val last = kept.lastOrNull()
                if (last != null && s.length > last.length &&
                    s.startsWith(last) && s[last.length] == '.'
                ) continue
                kept += s
            }

            val total = kept.sumOf { it.length }
            val data = CharArray(total)
            val offset = IntArray(kept.size + 1)
            var pos = 0
            kept.forEachIndexed { i, s ->
                offset[i] = pos
                s.toCharArray(data, pos)
                pos += s.length
            }
            offset[kept.size] = pos
            return DomainSet(data, offset)
        }

        /**
         * 解析各种域名表格式 —— 实测过几个源，都是一行能认出来的写法，没有元素隐藏 `##`、
         * 没有修饰符 `$`、没有正则：
         * ```
         * ||domain^          AdGuard / AWAvenue / close.txt
         * domain, <域名>      AdClose.txt
         * 0.0.0.0 domain      hosts 格式
         * domain              裸域名
         * ```
         * `@@` 开头的例外规则我们不支持，直接跳过（宁可不拦，也不能按反着的意思来）。
         */
        fun parse(text: String): List<String> {
            val out = ArrayList<String>(4096)
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") ||
                    line.startsWith("[")
                ) return@forEach

                val host = when {
                    line.startsWith("||") -> line.removePrefix("||").removeSuffix("^")
                        .substringBefore('$').substringBefore('/')
                    line.startsWith("domain,") -> line.removePrefix("domain,").trim()
                    line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") ->
                        line.substringAfter(' ').trim().substringBefore(' ')
                    line.startsWith("@@") -> return@forEach
                    else -> line
                }.trim()

                if (host.isNotEmpty() && host.length <= 253 &&
                    host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } &&
                    host.contains('.')
                ) out += host
            }
            return out
        }
    }
}
