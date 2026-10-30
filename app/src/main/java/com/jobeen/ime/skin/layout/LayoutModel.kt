package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /**
     * 点按动作；null = 文件未写 tap（仅内置布局文件允许，见
     * LayoutValidator.allowMissingTap）：沿用出厂键行为，不由
     * 布局文件定义。自创布局的 tap 必填，校验器保证非空。
     */
    val tap: SkinAction?,
    val swipeUp: SkinAction?,
    val swipeDown: SkinAction?,
    val longPress: List<SkinAction>?,
    /**
     * 横屏宽度权重（2026-10-27 并入项，仓模型）：横屏构建期取
     * 本值代替 widthWeight；null = 跟竖屏权重。文件存原始值，
     * 与 widthWeight 同口径永不归一化回写。
     */
    val landscapeWidthWeight: Double? = null,
    /** 单键样式引用（2026-10-27 并入项）：keyStyles 表中的样式名；null = 不引用。 */
    val styleRef: String? = null,
    /** 单键样式覆盖（2026-10-27 并入项）：逐角色取值，优先于引用样式。 */
    val styleOverrides: Map<KeyStyleRole, String>? = null,
)

/** 校验通过的布局（行序 = 文件数组序）。 */
data class LayoutModel(
    val layoutId: String,
    /** 显示名：meta.name，缺失时回退为 layoutId 本身（§2.1）。 */
    val name: String,
    val rows: List<List<LayoutKeyModel>>,
    /**
     * 布局专用高度百分比（2026-10-28 编辑器布局改版）：文件顶层
     * heightPercent，域 50–200；null = 未指定（走皮肤级 geometry /
     * App 设置）。键盘显示该布局时覆盖皮肤级 geometry 高度。
     */
    val heightPercent: Double? = null,
) {
    val keyCount: Int get() = rows.sumOf { it.size }

    fun logicalId(key: LayoutKeyModel): String = logicalId(layoutId, key.stableId)

    companion object {
        /** 逻辑 ID 拼装（定稿六.2 写死格式）。 */
        fun logicalId(layoutId: String, stableId: String): String = "$layoutId:K-$stableId"

        /**
         * 从 layout.json 原始树收集本布局全部逻辑 ID（G1 坐标覆写放宽口径）：
         * 只走 `rows[].keys[].stableId` 字符串，畸形行/键静默跳过——
         * 本函数不做布局校验（校验仍由 LayoutValidator 全权负责），
         * 仅供皮肤校验器在内置登记表之外另接受本布局的坐标 ID，
         * 使随包布局的坐标覆写在导入/快照工厂不被当成未知键剔掉。
         */
        fun logicalIdsFromTree(tree: JsonObject, layoutId: String): Set<String> {
            val rows = tree["rows"] as? JsonArray ?: return emptySet()
            val ids = LinkedHashSet<String>()
            for (rowNode in rows) {
                val keys = (rowNode as? JsonObject)?.get("keys") as? JsonArray ?: continue
                for (keyNode in keys) {
                    val stable = ((keyNode as? JsonObject)?.get("stableId") as? JsonPrimitive)
                        ?.contentOrNull
                    if (!stable.isNullOrEmpty()) ids += logicalId(layoutId, stable)
                }
            }
            return ids
        }

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
