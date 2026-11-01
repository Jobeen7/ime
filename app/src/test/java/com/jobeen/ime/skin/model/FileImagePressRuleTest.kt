package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fileImage 按压规则钉住（已定口径：高亮缺失时按压回落常态，
 * 绝不画空；对端按压全空属 bug，Jime 不跟随）：
 * - 描述帧查找：highlight 缺失 → 取 normal 帧。
 * - 工具栏背景解析：highlight 缺失或解码失败 → 常态 drawable
 *   （见 ToolbarButtonBackgroundResolver 的 `?: normal`）。
 * - 快照构建：常态 sheet 查无 → 整描述丢弃；高亮 sheet 查无
 *   → 高亮置空（渲染回落常态），见 SkinSnapshotBuilder。
 * 本测试钉住描述层查找；工具栏与快照两处均为读码验证的
 * 已有口径（无代码改动，见 Wave-8 报告 item 9）。
 */
class FileImagePressRuleTest {

    private val normalFrame = SpriteFrame(SpriteRect(0, 0, 10, 10))
    private val highlightFrame = SpriteFrame(SpriteRect(10, 0, 10, 10))

    private val maps = mapOf(
        "sheet" to mapOf("n" to normalFrame, "h" to highlightFrame),
    )

    private fun descriptor(highlight: SkinImageRef?) = SkinImageDescriptor.File(
        normal = SkinImageRef("sheet", "n"),
        highlight = highlight,
        contentMode = ImageContentMode.CENTER,
        insets = null,
    )

    @Test
    fun missingHighlightFallsBackToNormalFrame() {
        assertEquals(
            normalFrame,
            lookupSpriteFrame(descriptor(null), maps, highlight = true),
        )
        assertEquals(
            normalFrame,
            lookupSpriteFrame(descriptor(null), maps, highlight = false),
        )
    }

    @Test
    fun presentHighlightIsUsedOnPress() {
        assertEquals(
            highlightFrame,
            lookupSpriteFrame(
                descriptor(SkinImageRef("sheet", "h")), maps, highlight = true,
            ),
        )
        assertEquals(
            normalFrame,
            lookupSpriteFrame(
                descriptor(SkinImageRef("sheet", "h")), maps, highlight = false,
            ),
        )
    }

    @Test
    fun unknownHighlightFrameIsNotNormalFrame() {
        // 高亮子图名查无 → null（调用方按整图计，不画空帧）；
        // 与“高亮描述缺失 → 回落常态”是两条不同口径，不混淆。
        assertNull(
            lookupSpriteFrame(
                descriptor(SkinImageRef("sheet", "ghost")), maps, highlight = true,
            ),
        )
    }
}
