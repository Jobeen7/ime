package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 注解显示范围（E3 G44）：对标 对端 的 AnnotationInsetsTest——
 * 4 个内边距字段独立解析、空白继承、显式 0 覆盖、非法值拒绝，
 * 外加 Jime 的显示区／圆角间隙／可用宽度几何。
 */
class AnnotationDisplayRangeTest {

    private val fields = listOf("symbolInsetX", "symbolInsetY", "hintInsetX", "hintInsetY")

    @Test
    fun independentInsetsParseAndResolve() {
        val parsed = AnnotationDisplayRange.parse(
            fields.zip(listOf("8", "4", "6", "3.5")).toMap(),
        )
        assertEquals(
            AnnotationDisplayRange.Insets(8f, 4f, 6f, 3.5f),
            parsed,
        )
        assertEquals(
            AnnotationDisplayRange.ResolvedInsets(8f, 4f, 6f, 3.5f),
            AnnotationDisplayRange.resolve(parsed),
        )
    }

    @Test
    fun blankInheritsAndExplicitZeroOverridesNonzeroDefault() {
        val parsed = AnnotationDisplayRange.parse(
            mapOf("symbolInsetX" to "0", "hintInsetY" to ""),
        )
        assertEquals(
            AnnotationDisplayRange.Insets(symbolInsetXDp = 0f),
            parsed,
        )
        // 显式 0 留住（不回落缺省 4），空白字段取缺省（hintY 缺省 2）。
        assertEquals(
            AnnotationDisplayRange.ResolvedInsets(0f, 2f, 4f, 2f),
            AnnotationDisplayRange.resolve(parsed),
        )
        assertEquals(AnnotationDisplayRange.DEFAULT, AnnotationDisplayRange.resolve(null))
    }

    @Test
    fun invalidOrNonfiniteInsetValuesAreRejected() {
        fields.forEach { field ->
            listOf("-1", "49", "NaN", "Infinity", "text").forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    AnnotationDisplayRange.parse(mapOf(field to invalid))
                }
            }
        }
    }

    @Test
    fun displayRectInsetsByDefaultsAndClampsOnTinyKeys() {
        // 缺省 4dp 横向／2dp 纵向，density 3：300x120 键 → 12/6 内缩。
        val area = AnnotationDisplayRange.displayRect(
            300f, 120f, true, AnnotationDisplayRange.DEFAULT, 3f,
        )
        assertEquals(AnnotationDisplayRange.DisplayRect(12f, 6f, 288f, 114f), area)
        assertEquals(276f, area.width(), 0.001f)
        assertEquals(108f, area.height(), 0.001f)

        // 下方注解取 hint 内边距（缺省同样 4/2，显式区分）。
        val hintArea = AnnotationDisplayRange.displayRect(
            300f, 120f, false,
            AnnotationDisplayRange.ResolvedInsets(8f, 4f, 6f, 3f), 3f,
        )
        assertEquals(AnnotationDisplayRange.DisplayRect(18f, 9f, 282f, 111f), hintArea)

        // 键过小时内边距钳制在半宽／半高减 0.5 以内，显示区永不为空。
        val tiny = AnnotationDisplayRange.displayRect(
            20f, 10f, true, AnnotationDisplayRange.DEFAULT, 3f,
        )
        assertEquals(AnnotationDisplayRange.DisplayRect(9.5f, 4.5f, 10.5f, 5.5f), tiny)
        assertEquals(1f, tiny.width(), 0.001f)
        assertEquals(1f, tiny.height(), 0.001f)
    }

    @Test
    fun roundedClearanceIsZeroWithoutRadiusOrAtMidHeight() {
        assertEquals(0f, AnnotationDisplayRange.roundedClearance(0f, 20f, 0f, 300f, 120f), 0.001f)
        // 墨迹在中部（离顶／底都 >= 半径）时间隙为 0。
        assertEquals(0f, AnnotationDisplayRange.roundedClearance(50f, 70f, 24f, 300f, 120f), 0.001f)
    }

    @Test
    fun roundedClearanceGrowsTowardKeyEdge() {
        // 墨迹贴顶（distance 0）：间隙 = 整个半径。
        assertEquals(24f, AnnotationDisplayRange.roundedClearance(0f, 20f, 24f, 300f, 120f), 0.001f)
        // distance 12、半径 24：24 - sqrt(24² - 12²) ≈ 3.215。
        assertEquals(3.215f, AnnotationDisplayRange.roundedClearance(12f, 30f, 24f, 300f, 120f), 0.001f)
        // 半径钳制在半宽／半高以内：300x40 键、半径 60 → 按 20 算。
        assertEquals(20f, AnnotationDisplayRange.roundedClearance(0f, 20f, 60f, 300f, 40f), 0.001f)
    }

    @Test
    fun availableWidthNarrowsByCornerClearanceAndNeverNegative() {
        val area = AnnotationDisplayRange.DisplayRect(12f, 6f, 288f, 114f)
        // 墨迹贴顶：间隙 24，左 = max(12, 24) = 24，右 = min(288, 276) = 276 → 252。
        assertEquals(
            252f,
            AnnotationDisplayRange.availableWidth(area, 0f, 20f, 24f, 300f, 120f),
            0.001f,
        )
        // 墨迹在中部：间隙 0，可用宽度 = 显示区宽度。
        assertEquals(
            276f,
            AnnotationDisplayRange.availableWidth(area, 50f, 70f, 24f, 300f, 120f),
            0.001f,
        )
        // 极端小键：可用宽度钳制在 0，不为负。
        assertEquals(
            0f,
            AnnotationDisplayRange.availableWidth(
                AnnotationDisplayRange.DisplayRect(11.5f, 4.5f, 12.5f, 5.5f),
                0f, 10f, 12f, 24f, 120f,
            ),
            0.001f,
        )
    }
}
