package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.skin.model.SkinToolbarSnapshot

/**
 * 皮肤工具栏 vs 出厂默认的优先级抉择（纯函数，可直接单测；
 * [ToolbarRenderer] 与构造点只消费其结果，不另行分支）。
 *
 * 优先级（2026-10-27 工具栏进皮肤分流裁定，最优解）：
 * 1. 中央区：皮肤 `toolbar.buttons` 字段存在（快照 `buttons != null`，
 *    含显式空数组）→ 皮肤全权优先：清单与每钮图标都取皮肤，
 *    App 的中央区清单与单钮图标覆盖一律忽略。皮肤未定义中央区
 *    （`buttons == null`：无 toolbar 节点、节点无 buttons 字段、
 *    buttons 非数组、或原数组全被逐项丢弃）→ 用出厂默认五项
 *    （App 自定义配置已随第四组删除移除，无存储值）。
 * 2. 边缘图标：左右各独立。皮肤给了 expandIcon（最左侧，展开）
 *    / collapseIcon（最右侧，收起）且解码成功 → 用皮肤图标；
 *    否则用内置图标。App 侧没有边缘图标设置，无冲突。
 * 3. 剪贴板态工具栏（clipMode）不在此列：仍用内置返回/收起
 *    图标，剪贴板导航语义不随皮肤改变（见 ToolbarRenderer）。
 *
 * 图标回退在渲染层完成：[centerIconAssets] 只含皮肤声明的资产
 * id；解码失败/文件缺失的资产在图标表里查无，渲染时回退该钮
 * 的内置图标（见 ToolbarRenderer.iconForSkinButton）。符号名
 * 经 [centerIconSymbols] / 边缘符号字段原样透传，本分流无
 * 注册表故渲染期同样回退内置图标，注册表分流在此接线。
 */
object ToolbarSkinResolution {

    data class Resolved(
        /** 生效中央区动作（按皮肤或出厂默认的顺序）。 */
        val centerActions: List<PanelAction>,
        /** 皮肤中央区图标：动作 id → 资产逻辑 id（无皮肤中央区时为空）。 */
        val centerIconAssets: Map<String, String>,
        /**
         * 皮肤中央区符号名：动作 id → Material Symbols 名（原样保留；
         * 本分流无注册表，渲染层暂回退内置图标，注册表分流在此接线）。
         */
        val centerIconSymbols: Map<String, String>,
        /** 中央区是否由皮肤定义（true 时出厂默认不生效）。 */
        val hasSkinCenter: Boolean,
        /** 最左侧展开图标资产 id（null = 内置图标或符号名，见下）。 */
        val expandIconAsset: String?,
        /** 最右侧收起图标资产 id（null = 内置图标或符号名，见下）。 */
        val collapseIconAsset: String?,
        /** 最左侧展开图标符号名（null = 无符号；资产优先于符号）。 */
        val expandIconSymbol: String?,
        /** 最右侧收起图标符号名（null = 无符号；资产优先于符号）。 */
        val collapseIconSymbol: String?,
    )

    fun resolve(
        skinToolbar: SkinToolbarSnapshot?,
        fallbackActions: List<PanelAction>,
    ): Resolved {
        val skinButtons = skinToolbar?.buttons
        return if (skinButtons != null) {
            val actions = skinButtons.mapNotNull { ToolbarConfig.item(it.id)?.action }
            val assets = skinButtons.mapNotNull { button ->
                button.icon?.let { button.id to it.assetId }
            }.toMap()
            val symbols = skinButtons.mapNotNull { button ->
                button.symbol?.let { button.id to it }
            }.toMap()
            Resolved(
                centerActions = actions,
                centerIconAssets = assets,
                centerIconSymbols = symbols,
                hasSkinCenter = true,
                expandIconAsset = skinToolbar.expandIcon?.assetId,
                collapseIconAsset = skinToolbar.collapseIcon?.assetId,
                expandIconSymbol = skinToolbar.expandSymbol,
                collapseIconSymbol = skinToolbar.collapseSymbol,
            )
        } else {
            Resolved(
                centerActions = fallbackActions,
                centerIconAssets = emptyMap(),
                centerIconSymbols = emptyMap(),
                hasSkinCenter = false,
                expandIconAsset = skinToolbar?.expandIcon?.assetId,
                collapseIconAsset = skinToolbar?.collapseIcon?.assetId,
                expandIconSymbol = skinToolbar?.expandSymbol,
                collapseIconSymbol = skinToolbar?.collapseSymbol,
            )
        }
    }
}
