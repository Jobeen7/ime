package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 四变体回退链（2026-10-29 item-1）：双轴独立回退全矩阵。 */
class VariantResolverTest {

    @Test
    fun paletteAxis() {
        assertEquals(
            VariantPalette.DARK to null,
            VariantResolver.resolvePalette(true, true),
        )
        assertEquals(
            VariantPalette.LIGHT to VariantResolver.STEP_DARK_MISSING,
            VariantResolver.resolvePalette(true, false),
        )
        assertEquals(
            VariantPalette.LIGHT to null,
            VariantResolver.resolvePalette(false, true),
        )
        assertEquals(
            VariantPalette.LIGHT to null,
            VariantResolver.resolvePalette(false, false),
        )
    }

    @Test
    fun treeAxis() {
        assertEquals(
            VariantTree.LANDSCAPE to null,
            VariantResolver.resolveTree(true, true),
        )
        assertEquals(
            VariantTree.PORTRAIT to VariantResolver.STEP_LANDSCAPE_MISSING,
            VariantResolver.resolveTree(true, false),
        )
        assertEquals(
            VariantTree.PORTRAIT to null,
            VariantResolver.resolveTree(false, true),
        )
        assertEquals(
            VariantTree.PORTRAIT to null,
            VariantResolver.resolveTree(false, false),
        )
    }

    @Test
    fun combinedCornerFallsBackToLightPortraitWithBothSteps() {
        // 要深色横屏、两轴皆缺：最终（浅色，竖屏），两步皆记，
        // 调色轴记步在前、布局轴记步在后。
        val resolved = VariantResolver.resolve(
            wantDark = true, wantLandscape = true,
            hasDarkPalette = false, hasLandscapeTree = false,
        )
        assertEquals(VariantPalette.LIGHT, resolved.palette)
        assertEquals(VariantTree.PORTRAIT, resolved.tree)
        assertEquals(
            listOf(
                VariantResolver.STEP_DARK_MISSING,
                VariantResolver.STEP_LANDSCAPE_MISSING,
            ),
            resolved.steps,
        )
    }

    @Test
    fun combinedNoFallbackWhenAllPresent() {
        val resolved = VariantResolver.resolve(
            wantDark = true, wantLandscape = true,
            hasDarkPalette = true, hasLandscapeTree = true,
        )
        assertEquals(VariantPalette.DARK, resolved.palette)
        assertEquals(VariantTree.LANDSCAPE, resolved.tree)
        assertEquals(emptyList<String>(), resolved.steps)
    }
}
