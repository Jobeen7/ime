package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.SkinAction

/**
 * 自创布局强类型模型（B3 详设 §2.1/§2.2）：layout.json 校验通过后的
 * 只读视图。原始 JSON 树仍是存储真相（未知节点随文件往返保留，
 * 不进本模型）。
 *
 * 键身份 = 逻辑 ID `{layout_id}:K-<stableId>`：stableId 在键创建
 * 时生成并随文件落盘，身份与行列坐标分离——重排/增删其他键，
 * 存量键 ID 不变（定稿六.2）。
 */
data class LayoutKeyModel(
    val stableId: String,
    /** 展示文本：文件 label，缺失时按 §2.1 回退链（tap 文本 → 空串）后的有效值。 */
    val label: String,
    /** 宽度权重原始值（裁定 B1：文件存原始值，比例只在构建期算，永不归一化回写）。 */
    val widthWeight: Double,
    val rowSpan: Int,
    val alignSpanEnd: Boolean,
    val tap: SkinAction,
    val swipeUp: SkinAction?,
    val swipeDown: SkinAction?,
    val longPress: List<SkinAction>?,
)

/** 校验通过的布局（行序 = 文件数组序）。 */
data class LayoutModel(
    val layoutId: String,
    /** 显示名：meta.name，缺失时回退为 layoutId 本身（§2.1）。 */
    val name: String,
    val rows: List<List<LayoutKeyModel>>,
) {
    val keyCount: Int get() = rows.sumOf { it.size }

    fun logicalId(key: LayoutKeyModel): String = logicalId(layoutId, key.stableId)

    companion object {
        /** 逻辑 ID 拼装（定稿六.2 写死格式）。 */
        fun logicalId(layoutId: String, stableId: String): String = "$layoutId:K-$stableId"

        /** 逻辑 ID → (layoutId, stableId)；不合格式返回 null。 */
        fun parseLogicalId(logicalId: String): Pair<String, String>? {
            val marker = logicalId.indexOf(":K-")
            if (marker <= 0) return null
            val stableId = logicalId.substring(marker + 3)
            if (stableId.isEmpty()) return null
            return logicalId.substring(0, marker) to stableId
        }
    }
}
