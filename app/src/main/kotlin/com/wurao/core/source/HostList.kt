package com.wurao.core.source

/**
 * 广告域名表解析（L0「不让广告联网」的规则来源）。
 *
 * 实测过几份主流源（见 `docs/research-survey.md`），语法非常干净：
 * 99.99% 是 `||domain^` 或 `0.0.0.0 domain` 这类纯域名行，
 * 没有元素隐藏 `##`、没有修饰符 `$third-party`、没有正则。
 * 所以这里只做"认出域名"，不做通用过滤规则语法。
 *
 * 支持的写法：
 * - `||ads.example.com^`（AdGuard / ABP）
 * - `0.0.0.0 ads.example.com` / `127.0.0.1 ads.example.com`（hosts）
 * - `ads.example.com, 备注`（AdClose 那类）
 * - `#` 或 `!` 开头是注释，空行跳过
 */
object HostList {

    data class Result(
        val hosts: List<String>,
        /** 看不懂的行（不含域名特征），只记录不报错 —— 域名表来源杂，别因为一行坏的整体失败。 */
        val ignoredLines: Int,
    )

    fun parse(text: String): Result {
        val out = LinkedHashSet<String>()
        var ignored = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val host = hostOf(line)
            if (host == null) {
                ignored++
                return@forEach
            }
            out.add(host)
        }
        return Result(out.toList(), ignored)
    }

    /** 从一行里抠出域名。 */
    private fun hostOf(line: String): String? {
        var s = line
        // 去掉行内注释
        s = s.substringBefore("#").substringBefore("!").trim()
        // `||domain^` → domain
        if (s.startsWith("||")) {
            s = s.removePrefix("||").substringBefore("^").substringBefore("$").trim()
        } else if (s.startsWith("@@")) {
            // 例外规则（@@||domain^）：我们不做例外，跳过
            return null
        }
        // `0.0.0.0 domain` → 去掉前面的 IP
        val parts = s.split(Regex("\\s+"))
        val candidate = if (parts.size > 1 && parts[0].matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            parts[1]
        } else {
            parts[0]
        }
        // `domain, 备注` → 去掉逗号后的内容
        val host = candidate.substringBefore(",").trim()
        if (host.isEmpty() || !host.contains('.')) return null
        // 明显不是域名的（含协议、路径、通配符）丢掉
        if (host.contains('/') || host.contains('*')) return null
        return host.lowercase()
    }
}
