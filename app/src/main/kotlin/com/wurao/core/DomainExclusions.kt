package com.wurao.core

/**
 * 被用户单独移除的域名（ADclose 的 block 语义）：
 * 误屏蔽后能把那一条捞出去，重新导入订阅也不会把它带回来。
 * 落位时从域名表里减掉这份，改完调用 RuleStore.reland() 立即重落。
 */
object DomainExclusions {
    private val file = java.io.File("/data/data/com.wurao.app/files/domain_exclusions.txt")

    /** AdClose 的 "domain, xxx.com" → 纯域名；兼容纯域名写法。 */
    fun normalize(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("domain,", ignoreCase = true) -> t.removePrefix("domain,").trim()
            t.contains(",") -> t.substringAfter(",").trim()
            else -> t
        }
    }

    fun load(): Set<String> = runCatching {
        if (file.exists()) file.readLines().map { it.trim() }
            .filter { it.isNotBlank() }.toSet()
        else emptySet()
    }.getOrDefault(emptySet())

    private fun save(set: Set<String>) {
        runCatching { file.writeText(set.sorted().joinToString("\n")) }
    }

    fun add(domain: String) {
        val d = domain.trim()
        if (d.isNotBlank()) save(load() + d)
    }

    fun clear() = save(emptySet())
}
