package com.jobeen.ime.skin.model

/**
 * 明暗 × 横竖四变体回退链（2026-10-29 item-1）。
 *
 * 两轴独立回退（调色轴与布局轴互不影响），完整顺序：
 * - 调色轴：要深色且有深色板 → 深色；要深色但无深色板 →
 *   浅色（记步 dark_missing_fallback_light）；要浅色 → 浅色。
 * - 布局轴：要横屏树且横屏绑定已解析 → 横屏树；要横屏树但
 *   无横屏树 → 竖屏树（记步 landscape_missing_fallback_portrait）；
 *   要竖屏树 → 竖屏树。
 * - 组合角（要深色横屏、两轴皆缺）：先布局轴回退到竖屏树，
 *   再调色轴回退到浅色板，最终 (浅色，竖屏) —— 方向是硬设备
 *   状态、调色是视觉偏好，布局轴先行保证结构可用。
 * - 权重变体（同一树的竖屏权重／横屏权重）不在本链内：那是
 *   布局构建期的方向二选一（见 LayoutResolver），与树选择正交。
 * - 永不空白：两轴皆有兜底（浅色板恒存在、竖屏树恒为回退终点；
 *   竖屏树本身缺失时调用方再走方案默认键盘，不在本链内）。
 *
 * 纯函数，可直接单测；两处选择点经本链决策（调色见
 * SkinSnapshot.effectiveColorScheme，布局见 CustomLayoutRuntime）。
 */
enum class VariantPalette { LIGHT, DARK }

enum class VariantTree { PORTRAIT, LANDSCAPE }

data class ResolvedVariant(
    val palette: VariantPalette,
    val tree: VariantTree,
    /** 本次回退记步（空 = 无回退，要即所得）。 */
    val steps: List<String>,
)

object VariantResolver {

    const val STEP_DARK_MISSING = "dark_missing_fallback_light"
    const val STEP_LANDSCAPE_MISSING = "landscape_missing_fallback_portrait"

    /** 调色轴：返回（生效板，记步或 null）。 */
    fun resolvePalette(
        wantDark: Boolean,
        hasDarkPalette: Boolean,
    ): Pair<VariantPalette, String?> = when {
        wantDark && hasDarkPalette -> VariantPalette.DARK to null
        wantDark -> VariantPalette.LIGHT to STEP_DARK_MISSING
        else -> VariantPalette.LIGHT to null
    }

    /** 布局轴：返回（生效树，记步或 null）。 */
    fun resolveTree(
        wantLandscape: Boolean,
        hasLandscapeTree: Boolean,
    ): Pair<VariantTree, String?> = when {
        wantLandscape && hasLandscapeTree -> VariantTree.LANDSCAPE to null
        wantLandscape -> VariantTree.PORTRAIT to STEP_LANDSCAPE_MISSING
        else -> VariantTree.PORTRAIT to null
    }

    /** 双轴组合：记步按调色轴在前、布局轴在后排列。 */
    fun resolve(
        wantDark: Boolean,
        wantLandscape: Boolean,
        hasDarkPalette: Boolean,
        hasLandscapeTree: Boolean,
    ): ResolvedVariant {
        val (palette, paletteStep) = resolvePalette(wantDark, hasDarkPalette)
        val (tree, treeStep) = resolveTree(wantLandscape, hasLandscapeTree)
        return ResolvedVariant(
            palette = palette,
            tree = tree,
            steps = listOfNotNull(paletteStep, treeStep),
        )
    }
}
