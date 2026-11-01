package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图像描述机制（审计 D 项）：精灵图／描述解析／切片几何的纯层核对。 */
class SkinImageDescriptorTest {

    private fun parseDescriptor(json: String): Pair<SkinImageDescriptor?, List<SkinIssue>> {
        val issues = mutableListOf<SkinIssue>()
        val node: JsonElement = Json.parseToJsonElement(json)
        return parseImageDescriptor(node, "style", issues) to issues
    }

    @Test
    fun toolbarButtonBackgroundStyle_parsesPeerShape() {
        // 对端工具栏钮背景实测形：双帧圆图 + 样式层横向拉伸边 + 等比适应。
        val (descriptor, issues) = parseDescriptor(
            """{"buttonStyleType": "fileImage", "contentMode": "scaleAspectFit",
               "highlightImage": {"file": "toolbar_circle", "image": "IMG2"},
               "insets": {"bottom": 0, "left": 19, "right": 19, "top": 0},
               "normalImage": {"file": "toolbar_circle", "image": "IMG1"}}""",
        )
        assertTrue(issues.isEmpty())
        val file = descriptor as SkinImageDescriptor.File
        assertEquals(SkinImageRef("toolbar_circle", "IMG1"), file.normal)
        assertEquals(SkinImageRef("toolbar_circle", "IMG2"), file.highlight)
        assertEquals(ImageContentMode.SCALE_ASPECT_FIT, file.contentMode)
        assertEquals(SpriteInsets(left = 19, top = 0, right = 19, bottom = 0), file.insets)
        assertEquals(listOf("toolbar_circle"), file.sheetFiles())
    }

    @Test
    fun floatBackgroundStyle_defaultsToFillAndNoHighlight() {
        // 对端浮层背景实测形：常态／高亮同帧、无模式、无样式层 insets。
        val (descriptor, issues) = parseDescriptor(
            """{"buttonStyleType": "fileImage",
               "highlightImage": {"file": "float_back", "image": "IMG1"},
               "normalImage": {"file": "float_back", "image": "IMG1"}}""",
        )
        assertTrue(issues.isEmpty())
        val file = descriptor as SkinImageDescriptor.File
        assertEquals(SkinImageRef("float_back", "IMG1"), file.normal)
        assertEquals(SkinImageRef("float_back", "IMG1"), file.highlight)
        assertEquals(ImageContentMode.SCALE_TO_FILL, file.contentMode)
        assertNull(file.insets)
    }

    @Test
    fun spriteMap_parsesPeerYamlShape() {
        val issues = mutableListOf<SkinIssue>()
        val node: JsonElement = Json.parseToJsonElement(
            """{"IMG1": {"rect": {"x": 0, "y": 0, "width": 200, "height": 200}},
               "IMG2": {"rect": {"x": 200, "y": 0, "width": 200, "height": 200}}}""",
        )
        val map = parseSpriteMap(node, "toolbar_circle", issues)
        assertTrue(issues.isEmpty())
        assertEquals(SpriteFrame(SpriteRect(0, 0, 200, 200)), map["IMG1"])
        assertEquals(SpriteFrame(SpriteRect(200, 0, 200, 200)), map["IMG2"])
    }

    @Test
    fun spriteFrame_floatBackInsetsParsedAndClamped() {
        val issues = mutableListOf<SkinIssue>()
        val node: JsonElement = Json.parseToJsonElement(
            """{"rect": {"x": 0, "y": 0, "width": 130, "height": 130},
               "insets": {"top": 63, "bottom": 63, "left": 63, "right": 63}}""",
        )
        val frame = parseSpriteFrame(node, "float_back.IMG1", issues)
        assertTrue(issues.isEmpty())
        assertEquals(SpriteRect(0, 0, 130, 130), frame!!.rect)
        assertEquals(SpriteInsets(63, 63, 63, 63), frame.insets)
    }

    @Test
    fun effectiveInsets_styleBeatsFrameAndFrameBeatsZero() {
        val descriptor = SkinImageDescriptor.File(
            normal = SkinImageRef("sheet", "IMG1"),
            highlight = null,
            contentMode = ImageContentMode.SCALE_TO_FILL,
            insets = SpriteInsets(left = 19, top = 0, right = 19, bottom = 0),
        )
        val frame = SpriteFrame(SpriteRect(0, 0, 200, 200), SpriteInsets(10, 10, 10, 10))
        assertEquals(SpriteInsets(19, 0, 19, 0), effectiveCapInsets(descriptor, frame))
        assertEquals(SpriteInsets(10, 10, 10, 10), effectiveCapInsets(descriptor.copy(insets = null), frame))
        assertEquals(SpriteInsets.ZERO, effectiveCapInsets(descriptor.copy(insets = null), null))
    }

    @Test
    fun lookupFrame_missingSubImageFallsBackToWholeSheet() {
        val descriptor = SkinImageDescriptor.File(
            normal = SkinImageRef("sheet", "NOPE"),
            highlight = null,
            contentMode = ImageContentMode.SCALE_TO_FILL,
            insets = null,
        )
        assertNull(lookupSpriteFrame(descriptor, emptyMap(), false))
        val maps = mapOf("sheet" to mapOf("IMG1" to SpriteFrame(SpriteRect(0, 0, 50, 50))))
        assertNull(lookupSpriteFrame(descriptor, maps, false))
        assertEquals(
            SpriteFrame(SpriteRect(0, 0, 50, 50)),
            lookupSpriteFrame(descriptor.copy(normal = SkinImageRef("sheet", "IMG1")), maps, false),
        )
    }

    @Test
    fun cropRect_clampsAndRejectsOutside() {
        val frame = SpriteFrame(SpriteRect(180, 0, 100, 100))
        assertEquals(SpriteRect(180, 0, 20, 100), frame.cropRect(200, 200))
        assertNull(SpriteFrame(SpriteRect(300, 0, 10, 10)).cropRect(200, 200))
        assertNull(SpriteFrame(SpriteRect(0, 0, 10, 10)).cropRect(0, 0))
    }

    @Test
    fun contentModeScaleTypeNames_matchAndroidScaleTypes() {
        assertEquals("FIT_XY", ImageContentMode.SCALE_TO_FILL.scaleTypeName)
        assertEquals("FIT_CENTER", ImageContentMode.SCALE_ASPECT_FIT.scaleTypeName)
        assertEquals("CENTER_CROP", ImageContentMode.SCALE_ASPECT_FILL.scaleTypeName)
        assertEquals("CENTER", ImageContentMode.CENTER.scaleTypeName)
        assertEquals(ImageContentMode.SCALE_ASPECT_FILL, ImageContentMode.byJsonName("scaleAspectFill"))
        assertNull(ImageContentMode.byJsonName("stretch"))
    }

    @Test
    fun invalidDescriptorInputs_fallBackFieldWise() {
        // 常态缺失 → 整描述 null；未知模式 → 默认铺满 + issue；未知类型 → null。
        val (noNormal, _) = parseDescriptor("""{"buttonStyleType": "fileImage"}""")
        assertNull(noNormal)
        val (badMode, badModeIssues) = parseDescriptor(
            """{"buttonStyleType": "fileImage", "contentMode": "stretch",
               "normalImage": {"file": "s", "image": "IMG1"}}""",
        )
        val badFile = badMode as SkinImageDescriptor.File
        assertEquals(ImageContentMode.SCALE_TO_FILL, badFile.contentMode)
        assertTrue(badModeIssues.any { it.reason == "content_mode_invalid" })
        val (badType, badTypeIssues) = parseDescriptor(
            """{"buttonStyleType": "geometry", "normalColor": "#FFFFFFFF"}""",
        )
        assertNull(badType)
        assertTrue(badTypeIssues.any { it.reason == "image_descriptor_type_invalid" })
    }

    @Test
    fun assetImage_parsesNameAndSheetsEmpty() {
        val (descriptor, issues) = parseDescriptor(
            """{"buttonStyleType": "assetImage", "name": "toolbar_menu"}""",
        )
        assertTrue(issues.isEmpty())
        assertEquals(SkinImageDescriptor.Asset("toolbar_menu"), descriptor)
        assertTrue(descriptor!!.sheetFiles().isEmpty())
    }

    @Test
    fun ninePatchSlices_cornerFixedCenterStretched() {
        // 200 宽帧、左右边框 19：目标 100 宽时边框各 19、中间拉伸段 62。
        val slices = ninePatchSlices(
            sheetWidth = 400, sheetHeight = 200,
            frame = SpriteRect(0, 0, 200, 200),
            insets = SpriteInsets(left = 19, top = 0, right = 19, bottom = 0),
            dstWidth = 100f, dstHeight = 100f,
        )
        // 垂直无边框 → 仅中间一行；水平三段。
        assertEquals(3, slices.size)
        assertEquals(NinePatchSlice(0, 0, 19, 200, 0f, 0f, 19f, 100f), slices[0])
        assertEquals(NinePatchSlice(19, 0, 181, 200, 19f, 0f, 81f, 100f), slices[1])
        assertEquals(NinePatchSlice(181, 0, 200, 200, 81f, 0f, 100f, 100f), slices[2])
    }

    @Test
    fun ninePatchSlices_fullCoverReturnsEmptyForContentModeFallback() {
        val slices = ninePatchSlices(
            sheetWidth = 130, sheetHeight = 130,
            frame = SpriteRect(0, 0, 130, 130),
            insets = SpriteInsets(left = 70, top = 70, right = 70, bottom = 70),
            dstWidth = 100f, dstHeight = 100f,
        )
        assertTrue(slices.isEmpty())
    }

    @Test
    fun contentModeDstRect_fitCentersAndFillCrops() {
        val fit = contentModeDstRect(ImageContentMode.SCALE_ASPECT_FIT, 200, 100, 100f, 100f)
        assertEquals(ContentModeRect(0f, 25f, 100f, 75f), fit)
        val fill = contentModeDstRect(ImageContentMode.SCALE_ASPECT_FILL, 100, 200, 100f, 100f)
        assertEquals(ContentModeRect(-50f, 0f, 150f, 100f), fill)
        val center = contentModeDstRect(ImageContentMode.CENTER, 20, 20, 100f, 100f)
        assertEquals(ContentModeRect(40f, 40f, 60f, 60f), center)
        assertFalse(fit == fill)
    }
}
