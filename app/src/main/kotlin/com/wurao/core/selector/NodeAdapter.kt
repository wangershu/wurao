package com.wurao.core.selector

/**
 * 节点适配器：**整套匹配器与节点来源无关的关键抽象**（这个思路照 GKD）。
 *
 * 无障碍侧实现的是 `AccessibilityNodeInfo` 版，我们进程内实现 View 版 ——
 * 匹配算法、选择器、规则格式完全不用改。
 *
 * 契约（必须遵守，否则匹配结果不确定）：
 * - 一次匹配过程中，同一节点必须返回**相同**的结果（进程内要防 UI 线程改树）；
 * - [key] 必须在这一次匹配中保持稳定，失败记忆化靠它。
 */
interface NodeAdapter<T> {

    /**
     * 取属性值。path 可能是 `text`、`childCount`、`text.length`。
     * 取不到返回 null（比较时按 null 传播处理）。
     */
    fun attr(node: T, path: List<String>): Any?

    /** 节点类名（对应选择器里的类名段）。 */
    fun className(node: T): String?

    fun childCount(node: T): Int

    fun child(node: T, index: Int): T?

    fun parent(node: T): T?

    /** 稳定标识，用于失败记忆化。同一节点在一次匹配中必须返回同一个 key。 */
    fun key(node: T): Any
}
