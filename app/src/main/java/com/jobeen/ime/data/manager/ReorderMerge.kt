package com.jobeen.ime.data.manager

/**
 * 拖动排序的槽位保持合并：[current] 是全量条目的当前显示序，
 * [shownInNewOrder] 是面板显示子集的新顺序。结果中，显示条目在它们
 * 原先占据的槽位集合内按新序排列，未显示条目（保留期外的旧条目、
 * 落库前刚新增的条目）保持原位——避免只按子集拼接时未显示条目被
 * 一律压到末尾、或新旧两套序号交错。
 */
internal fun <T, K> slotPreservingReorder(
    current: List<T>,
    shownInNewOrder: List<T>,
    key: (T) -> K,
): List<T> {
    if (shownInNewOrder.isEmpty()) return current
    val shownKeys = shownInNewOrder.mapTo(HashSet()) { key(it) }
    var qi = 0
    return current.map { item ->
        if (key(item) in shownKeys && qi < shownInNewOrder.size) {
            shownInNewOrder[qi++]
        } else {
            item
        }
    }
}
