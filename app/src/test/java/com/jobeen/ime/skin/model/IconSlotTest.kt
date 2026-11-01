package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通用图标槽：字符串 / 平台映射 / material: 前缀 / SF→Material 起始表 / 未配置。 */
class IconSlotTest {

    @Test
    fun stringFormStripsMaterialPrefix() {
        assertEquals(
            "backspace",
            IconSlot.resolveAndroidName(Json.parseToJsonElement("\"material:backspace\"")),
        )
        assertEquals(
            "backspace",
            IconSlot.resolveAndroidName(Json.parseToJsonElement("\"backspace\"")),
        )
        assertNull(IconSlot.resolveAndroidName(Json.parseToJsonElement("\"\"")))
    }

    @Test
    fun platformMapPrefersAndroid() {
        assertEquals(
            "backspace",
            IconSlot.resolveAndroidName(
                Json.parseToJsonElement(
                    """{"ios": "delete.left", "android": "backspace"}""",
                ),
            ),
        )
        // android 带前缀照剥。
        assertEquals(
            "backspace",
            IconSlot.resolveAndroidName(
                Json.parseToJsonElement(
                    """{"ios": "delete.left", "android": "material:backspace"}""",
                ),
            ),
        )
    }

    @Test
    fun missingAndroidFallsBackToSfTableThenUnconfigured() {
        // 仅 ios 且在起始表内 → 表值（文档原例 delete.left → backspace）。
        assertEquals(
            "backspace",
            IconSlot.resolveAndroidName(
                Json.parseToJsonElement("""{"ios": "delete.left"}"""),
            ),
        )
        // 仅 ios 且不在表内 → 未配置（不猜测）。
        assertNull(
            IconSlot.resolveAndroidName(
                Json.parseToJsonElement("""{"ios": "some.unknown.glyph"}"""),
            ),
        )
        // android 空值 = 未配置键，不回退到 ios 之外的东西（ios 在表内才回落）。
        assertNull(
            IconSlot.resolveAndroidName(
                Json.parseToJsonElement("""{"ios": "some.unknown.glyph", "android": ""}"""),
            ),
        )
        // 非小写键忽略。
        assertNull(
            IconSlot.resolveAndroidName(
                Json.parseToJsonElement("""{"Android": "backspace"}"""),
            ),
        )
    }

    @Test
    fun keyIconParsingAcceptsPlatformMap() {
        val parsed = SkinKeyMappingsParser.parse(
            Json.parseToJsonElement(
                """{"back_key": {
                    "icon": {"ios": "delete.left", "android": "backspace"},
                    "pressedIcon": {"ios": "delete.left.fill"}
                }}""",
            ) as JsonObject,
            isOverridable = { true },
            isAnchor = { false },
        )
        val override = parsed.overrides["back_key"]
        assertEquals("backspace", override?.icon)
        // 按压态映射缺 android 时以起始表查 ios（fill 变体同名映射）。
        assertEquals("backspace", override?.pressedIcon)
    }
}
