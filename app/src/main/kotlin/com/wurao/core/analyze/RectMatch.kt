package com.wurao.core.analyze

/**
 * 屏幕矩形匹配：抓屏时框选的区域 vs View 树里的 View 矩形。
 *
 * 与 App 结构无关的通杀定位：不看 className、不看 clickable、不看父子关系，
 * 只比较谁跟框选区域最吻合（IoU）。tab 容器会得 1.0，里面的图标约 0.1，
 * 整条底栏约 0.2 —— 自然选中「用户框的那一整块」，而不是某个子 View。
 */
object RectMatch {

    /** 解析 "l,t,r,b"；非法返回 null。 */
    fun parse(raw: String): IntArray? {
        val p = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (p.size != 4) return null
        if (p[2] <= p[0] || p[3] <= p[1]) return null
        return p.toIntArray()
    }

    fun of(l: Int, t: Int, r: Int, b: Int): IntArray = intArrayOf(l, t, r, b)

    /** 交并比，0f~1f；矩形无效或不相交返回 0f。 */
    fun iou(a: IntArray, b: IntArray): Float {
        if (a.size != 4 || b.size != 4) return 0f
        val aw = a[2] - a[0]; val ah = a[3] - a[1]
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        if (aw <= 0 || ah <= 0 || bw <= 0 || bh <= 0) return 0f
        val iw = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val ih = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih
        val union = aw.toLong() * ah + bw.toLong() * bh - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }
}
