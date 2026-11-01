package com.wurao.core

/**
 * 广告域名匹配。
 *
 * 为什么单独拎出来：DNS 拦截挂在 `InetAddress.getByName` 上，是**网络热路径** ——
 * 每次域名解析都要过一遍。订阅动辄一两千个域名，逐个比对是 O(n) 且发生在每次解析上，
 * 那本身就是耗电。这里改成哈希 + 逐级后缀，一次匹配只做几次哈希查找。
 */
object HostMatcher {

    /**
     * @param host 待判定的域名（可以带端口或前导点，会先规整）
     * @param hosts 广告域名集合（小写）；命中规则：完全相等，或它的某一级后缀在集合里
     */
    fun matches(host: String?, hosts: Set<String>): Boolean {
        if (host.isNullOrBlank() || hosts.isEmpty()) return false
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (h in hosts) return true
        // 逐级取后缀：a.b.c.com -> b.c.com -> c.com
        var dot = h.indexOf('.')
        while (dot > 0 && dot < h.length - 1) {
            if (h.substring(dot + 1) in hosts) return true
            dot = h.indexOf('.', dot + 1)
        }
        return false
    }

    /** 去掉协议、路径、端口、末尾的点，转小写。 */
    fun normalize(host: String): String {
        var s = host.trim()
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?')
        s = s.substringBefore(':')
        s = s.trim().trimEnd('.')
        return s.lowercase()
    }
}
