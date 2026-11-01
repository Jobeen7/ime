package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** YAML 精灵图描述文件：块形态 / 流式形态 / 校验丢弃 / 文件胜出合并。 */
class YamlSpriteDescriptorTest {

    @Test
    fun blockFormParsesRectAndInsets() {
        val parsed = YamlSpriteDescriptor.parse(
            """
            button_normal:
              rect:
                x: 0
                y: 10
                width: 100
                height: 50
              insets:
                top: 1
                bottom: 2
                left: 3
                right: 4
            """.trimIndent(),
        )
        assertTrue(parsed.issues.isEmpty())
        val frame = parsed.frames["button_normal"]
        assertEquals(SpriteRect(0, 10, 100, 50), frame?.rect)
        assertEquals(SpriteInsets(left = 3, top = 1, right = 4, bottom = 2), frame?.insets)
    }

    @Test
    fun flowFormAndDefaults() {
        val parsed = YamlSpriteDescriptor.parse(
            """
            # 注释行跳过
            btn:
              rect: {x: 5, y: 6, width: 20, height: 30}
            """.trimIndent(),
        )
        assertTrue(parsed.issues.isEmpty())
        assertEquals(SpriteRect(5, 6, 20, 30), parsed.frames["btn"]?.rect)
        assertEquals(SpriteInsets.ZERO, parsed.frames["btn"]?.insets)
    }

    @Test
    fun invalidEntriesAreDroppedWithIssues() {
        val parsed = YamlSpriteDescriptor.parse(
            """
            no_rect:
              insets:
                top: 1
            bad_size:
              rect:
                x: 0
                y: 0
                width: 0
                height: 10
            negative_inset:
              rect:
                x: 0
                y: 0
                width: 10
                height: 10
              insets:
                top: -1
            ok:
              rect:
                x: 0
                y: 0
                width: 10
                height: 10
            """.trimIndent(),
        )
        assertEquals(setOf("ok"), parsed.frames.keys)
        assertEquals(3, parsed.issues.size)
    }

    @Test
    fun fileFormWinsMergeAndReportsConflicts() {
        val json = mapOf(
            "sheet" to mapOf(
                "a" to SpriteFrame(SpriteRect(0, 0, 10, 10)),
                "b" to SpriteFrame(SpriteRect(0, 0, 20, 20)),
            ),
        )
        val file = mapOf(
            "sheet" to mapOf(
                // a 帧不同 → 文件胜并记冲突；b 等值 → 覆盖但不记冲突。
                "a" to SpriteFrame(SpriteRect(1, 1, 10, 10)),
                "b" to SpriteFrame(SpriteRect(0, 0, 20, 20)),
                "c" to SpriteFrame(SpriteRect(0, 0, 30, 30)),
            ),
        )
        val merged = YamlSpriteDescriptor.mergeSpriteMaps(json, file)
        assertEquals(SpriteRect(1, 1, 10, 10), merged.maps["sheet"]?.get("a")?.rect)
        assertEquals(SpriteRect(0, 0, 30, 30), merged.maps["sheet"]?.get("c")?.rect)
        assertEquals(
            listOf(YamlSpriteDescriptor.Conflict("sheet", "a")),
            merged.conflicts,
        )
    }
}
