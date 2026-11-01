package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 键面图片内容模式（审计 D 项）：键条目与前景层解析口径。 */
class KeyImageContentModeTest {

    private fun parseEntry(json: String): KeyOverride? {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinKeyMappingsParser.parse(tree, { true }, { false })
        return result.overrides.values.singleOrNull()
    }

    @Test
    fun faceAndPressedModes_parsedFromKeyEntry() {
        val override = parseEntry(
            """{"KEY_A": {"image": "img-a",
               "imageContentMode": "scaleAspectFit",
               "pressedImage": "img-a-pressed",
               "pressedImageContentMode": "center"}}""",
        )
        // image/模式字段随覆写一并解析（image 本身即构成覆写）。
        assertEquals(ImageContentMode.SCALE_ASPECT_FIT, override?.imageContentMode)
        assertEquals(ImageContentMode.CENTER, override?.pressedImageContentMode)
    }

    @Test
    fun invalidMode_droppedWithWarningAndImageKept() {
        val tree = Json.parseToJsonElement(
            """{"KEY_A": {"image": "img-a",
               "imageContentMode": "stretch"}}""",
        ) as JsonObject
        val result = SkinKeyMappingsParser.parse(tree, { true }, { false })
        val override = result.overrides.values.singleOrNull()
        assertEquals("img-a", override?.image)
        assertNull(override?.imageContentMode)
        assertEquals(
            listOf("content_mode_invalid"),
            result.fieldWarnings.map { it.reason },
        )
    }

    @Test
    fun foregroundImageLayerMode_parsedAndInvalidDropped() {
        val tree = Json.parseToJsonElement(
            """{"KEY_A": {
               "foregroundLayers": [
                 {"type": "image", "image": "img-fg", "contentMode": "scaleAspectFill"},
                 {"type": "image", "image": "img-fg2", "contentMode": "nope"}]}}""",
        ) as JsonObject
        val result = SkinKeyMappingsParser.parse(tree, { true }, { false })
        val layers = result.overrides.values.singleOrNull()?.foregroundLayers
        assertEquals(2, layers?.size)
        assertEquals(ImageContentMode.SCALE_ASPECT_FILL, layers?.get(0)?.contentMode)
        assertNull(layers?.get(1)?.contentMode)
        assertEquals(
            listOf("content_mode_invalid"),
            result.fieldWarnings.map { it.reason },
        )
    }
}
