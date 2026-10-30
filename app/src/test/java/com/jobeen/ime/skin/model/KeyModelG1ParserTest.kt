package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1 keyMappings 新增字段解析：左右滑、图片、副文字、字号、几何、前景、动态文字。 */
class KeyModelG1ParserTest {

    private fun parse(json: String): SkinKeyMappingsParser.ParseResult {
        val tree = Json.parseToJsonElement(json) as JsonObject
        // 本测试放宽可覆写判定（不依赖内置登记表具体条目）。
        return SkinKeyMappingsParser.parse(tree, { true }, { false })
    }

    private fun parseEntry(entryJson: String): SkinKeyMappingsParser.ParseResult =
        parse("""{"K1": $entryJson}""")

    private fun entry(result: SkinKeyMappingsParser.ParseResult): KeyOverride =
        result.overrides.getValue("K1")

    @Test
    fun swipeLeftRightParsed() {
        val result = parseEntry(
            """{
              "swipeLeft": {"type": "command", "action": "cursor_left"},
              "swipeRight": {"type": "command", "action": "cursor_right"}
            }""",
        )
        assertTrue(result.rejections.isEmpty())
        assertEquals(
            SkinAction.Command(WhitelistAction.CURSOR_LEFT),
            entry(result).swipeLeft,
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.CURSOR_RIGHT),
            entry(result).swipeRight,
        )
    }

    @Test
    fun swipeThresholdParsedAndInvalidDropped() {
        val result = parseEntry("""{"swipeThresholdDp": 24}""")
        assertEquals(24f, entry(result).swipeThresholdDp ?: -1f, 0.001f)
        val bad = parseEntry("""{"swipeThresholdDp": 200}""")
        assertTrue(bad.fieldWarnings.any { it.field == "swipeThresholdDp" })
        assertTrue(bad.overrides.isEmpty())
        val text = parseEntry("""{"swipeThresholdDp": "24"}""")
        assertTrue(text.fieldWarnings.any { it.field == "swipeThresholdDp" })
    }

    @Test
    fun voiceInputRejectedInHorizontalSlots() {
        val result = parseEntry(
            """{"swipeLeft": {"type": "command", "action": "voice_input"}}""",
        )
        assertTrue(result.rejections.any { it.reason == BindingRejectReason.BAD_SLOT })
        assertTrue(result.overrides.isEmpty())
    }

    @Test
    fun imageAndSubLabelParsed() {
        val result = parseEntry("""{"image": "img_key", "subLabel": "小字"}""")
        assertTrue(result.rejections.isEmpty())
        assertEquals("img_key", entry(result).image)
        assertEquals("小字", entry(result).subLabel)
    }

    @Test
    fun emptyImageDroppedAndEmptySubLabelIsAbsent() {
        val result = parseEntry("""{"image": "", "subLabel": ""}""")
        // image 空串字段级回退；subLabel 空串归一为无附加文字（不记拒绝）。
        assertTrue(result.rejections.any { it.reason == BindingRejectReason.STRUCTURE })
        assertTrue(result.overrides.isEmpty())
        val subOnly = parseEntry("""{"subLabel": ""}""")
        assertTrue(subOnly.rejections.isEmpty())
        assertTrue(subOnly.overrides.isEmpty())
    }

    @Test
    fun subLabelTooLongDropped() {
        val result = parseEntry("""{"subLabel": "12345678901234567"}""")
        assertTrue(result.rejections.isNotEmpty())
        assertTrue(result.overrides.isEmpty())
    }

    @Test
    fun subLabelTopParsedAlongsideBottom() {
        // 顶部＋底部双行小字同时解析；顶部空串归一为无、超长丢弃。
        val result = parseEntry("""{"subLabel": "下", "subLabelTop": "上"}""")
        assertTrue(result.rejections.isEmpty())
        assertEquals("下", entry(result).subLabel)
        assertEquals("上", entry(result).subLabelTop)
        val emptyTop = parseEntry("""{"subLabelTop": ""}""")
        assertTrue(emptyTop.rejections.isEmpty())
        assertTrue(emptyTop.overrides.isEmpty())
        val tooLong = parseEntry("""{"subLabelTop": "12345678901234567"}""")
        assertTrue(tooLong.rejections.isNotEmpty())
        assertTrue(tooLong.overrides.isEmpty())
    }

    @Test
    fun textSizesInvalidEntriesDroppedPerRole() {
        val result = parseEntry(
            """{"textSizes": {"main": 20, "sub": 5, "nope": 12, "symbol": "18"}}""",
        )
        assertEquals(mapOf(KeyTextRole.MAIN to 20f), entry(result).textSizes)
        assertEquals(3, result.fieldWarnings.size)
    }

    @Test
    fun geometryInvalidFieldDroppedPerField() {
        val result = parseEntry(
            """{"geometry": {"widthFactor": 1.5, "heightFactor": 1.8, "contentCenterX": 0.3}}""",
        )
        val geometry = entry(result).geometry!!
        assertEquals(1.5f, geometry.widthFactor)
        assertNull(geometry.heightFactor) // 1.8 超出 0.5..1.0
        assertEquals(0.3f, geometry.contentCenterX)
        assertEquals(1, result.fieldWarnings.size)
    }

    @Test
    fun foregroundLayersValidated() {
        val result = parseEntry(
            """{"foregroundLayers": [
              {"type": "text", "text": "角标", "color": "#FFFF0000", "size": 10},
              {"type": "image", "image": "img_badge"},
              {"type": "video", "text": "x"},
              {"type": "text", "text": ""},
              {"type": "text", "text": "第四层"},
              {"type": "text", "text": "第五层"},
              {"type": "text", "text": "超限层"}
            ]}""",
        )
        // 有效 4 层（角标、图片、第四层、第五层）+ 2 坏层丢弃 + 1 超限层丢弃
        assertEquals(4, entry(result).foregroundLayers!!.size)
        assertEquals("text", entry(result).foregroundLayers!![0].type)
        assertEquals("image", entry(result).foregroundLayers!![1].type)
        assertEquals("第四层", entry(result).foregroundLayers!![2].text)
        assertEquals("第五层", entry(result).foregroundLayers!![3].text)
        assertEquals(3, result.fieldWarnings.size)
    }

    @Test
    fun dynamicTextParsedAndInvalidDropped() {
        val ok = parseEntry("""{"dynamicText": "candidateFirst"}""")
        assertEquals(DynamicTextSource.CANDIDATE_FIRST, entry(ok).dynamicText)
        val bad = parseEntry("""{"dynamicText": "clipboard"}""")
        assertTrue(bad.rejections.isNotEmpty())
        assertTrue(bad.overrides.isEmpty())
    }

    @Test
    fun allNewFieldsEmptyMeansNoOverride() {
        val result = parseEntry("""{"geometry": {"widthFactor": 3.0}}""")
        // 唯一字段非法被丢 → 该键完全回退出厂，不进覆写表
        assertTrue(result.overrides.isEmpty())
    }
}
