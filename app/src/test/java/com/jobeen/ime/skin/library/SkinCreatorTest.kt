// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkinCreator 合成口径测试（2026-11-16 皮肤编辑器修法 1(a)＋2）。
 * 只测纯函数部分；入库＋激活走 ImportTransaction/SkinApplier
 * 的既有测试链（ImportTransactionTest / SkinLibraryUiTest）。
 *
 * 色板一律手工构造（字面 Int）：KeyboardThemePresets／MonetPalette
 * 的静态初始化调 android.graphics.Color，JVM 单测不可用；生产侧
 * 由 SkinCreator.PresetBoards.current() 解析传入。
 */
class SkinCreatorTest {

    /** 以 base 为基底构造一整套配色（各字段取 base＋偏移，便于区分）。 */
    private fun scheme(base: Int): KeyboardColors.ColorScheme {
        fun c(offset: Int): Int = base + offset
        return KeyboardColors.ColorScheme(
            keyBackground = c(0x01),
            keyPressed = c(0x02),
            keyBorderStroke = c(0x03),
            specialKeyBackground = c(0x04),
            specialKeyPressed = c(0x05),
            specialKeyBorderStroke = c(0x06),
            accentKeyBackground = c(0x07),
            accentKeyPressed = c(0x08),
            accentKeyBorderStroke = c(0x09),
            keyText = c(0x0A),
            specialKeyText = c(0x0B),
            accentKeyText = c(0x0C),
            altText = c(0x0D),
            background = c(0x0E),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = c(0x10),
                toolbarText = c(0x11),
                toolbarActived = c(0x12),
                toolbarIcon = c(0x13),
                candidateBackground = c(0x14),
                candidateText = c(0x15),
                candidateIndex = c(0x16),
                candidateDivider = c(0x17),
                toolbarPressed = c(0x18),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = c(0x20),
                textColor = c(0x21),
                secondaryTextColor = c(0x22),
            ),
        )
    }

    private val lightScheme = scheme(0xFF101010.toInt())
    private val darkScheme = scheme(0xFF202020.toInt())
    private val monetLightScheme = scheme(0xFF303030.toInt())
    private val monetDarkScheme = scheme(0xFF404040.toInt())
    private val sunsetScheme = scheme(0xFF505050.toInt())

    private val presets = SkinCreator.PresetBoards(
        defaultLight = lightScheme,
        defaultDark = darkScheme,
        monetLight = monetLightScheme,
        monetDark = monetDarkScheme,
    )

    private val defaultBoards = SkinCreator.Boards(
        light = lightScheme,
        dark = darkScheme,
    )

    private val lightTheme = KeyboardTheme(
        id = KeyboardTheme.LIGHT_DEFAULT_ID,
        name = "素白",
        colors = lightScheme,
    )
    private val darkTheme = KeyboardTheme(
        id = KeyboardTheme.DARK_DEFAULT_ID,
        name = "暗夜",
        colors = darkScheme,
    )
    private val monetTheme = KeyboardTheme(
        id = MonetPalette.ID,
        name = "莫奈",
        colors = monetLightScheme,
    )
    private val sunsetTheme = KeyboardTheme(
        id = "sunset",
        name = "Sunset",
        colors = sunsetScheme,
    )

    private fun parseSkinJson(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject

    @Test
    fun buildSkinJson_hasFormatVersion3AndMeta() {
        val tree = parseSkinJson(
            SkinCreator.buildSkinJson("builtin-default", "默认 副本", defaultBoards),
        )
        assertEquals(3, tree["formatVersion"]!!.jsonPrimitive.content.toInt())
        val meta = tree["meta"]!!.jsonObject
        assertEquals("builtin-default", meta["id"]!!.jsonPrimitive.content)
        assertEquals("默认 副本", meta["name"]!!.jsonPrimitive.content)
        assertEquals("BIN", meta["author"]!!.jsonPrimitive.content)
        // 无布局绑定：运行期回退出厂布局。
        assertNull(tree["layoutId"])
        assertNull(tree["keyboards"])
        assertTrue(tree.containsKey("styles"))
        assertTrue(tree.containsKey("stylesDark"))
    }

    @Test
    fun buildSkinJson_boardsAreSplitLightDark() {
        val tree = parseSkinJson(
            SkinCreator.buildSkinJson("builtin-default", "默认 副本", defaultBoards),
        )
        val styles = tree["styles"]!!.jsonObject
        val stylesDark = tree["stylesDark"]!!.jsonObject
        assertEquals(
            SkinJson.formatColor(lightScheme.keyBackground),
            styles["keyBackground"]!!.jsonPrimitive.content,
        )
        assertEquals(
            SkinJson.formatColor(darkScheme.keyBackground),
            stylesDark["keyBackground"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun buildSkinJson_allColorsAreAarrggbb() {
        val tree = parseSkinJson(
            SkinCreator.buildSkinJson("builtin-default", "默认 副本", defaultBoards),
        )
        val hex = Regex("#[0-9A-F]{8}")
        fun check(obj: JsonObject) {
            for ((_, value) in obj) {
                when (value) {
                    is JsonObject -> check(value)
                    else -> assertTrue(
                        "not #AARRGGBB: ${value.jsonPrimitive.content}",
                        hex.matches(value.jsonPrimitive.content),
                    )
                }
            }
        }
        check(tree["styles"]!!.jsonObject)
        check(tree["stylesDark"]!!.jsonObject)
    }

    @Test
    fun boardsFor_defaultFamilyComplements() {
        val fromLight = SkinCreator.boardsFor(lightTheme, presets)
        assertEquals(lightScheme, fromLight.light)
        assertEquals(darkScheme, fromLight.dark)
        val fromAmoled = SkinCreator.boardsFor(darkTheme, presets)
        assertEquals(lightScheme, fromAmoled.light)
        assertEquals(darkScheme, fromAmoled.dark)
    }

    @Test
    fun boardsFor_monetUsesFallbacks() {
        val boards = SkinCreator.boardsFor(monetTheme, presets)
        assertEquals(monetLightScheme, boards.light)
        assertEquals(monetDarkScheme, boards.dark)
    }

    @Test
    fun boardsFor_otherThemeUsesOwnColorsBothBoards() {
        val boards = SkinCreator.boardsFor(sunsetTheme, presets)
        assertEquals(sunsetScheme, boards.light)
        assertEquals(sunsetScheme, boards.dark)
    }

    @Test
    fun copyIdFamily() {
        assertEquals("default", SkinCreator.copyIdFamily(lightTheme))
        assertEquals("default", SkinCreator.copyIdFamily(darkTheme))
        assertEquals("monet", SkinCreator.copyIdFamily(monetTheme))
        assertEquals(
            sunsetTheme.id,
            SkinCreator.copyIdFamily(sunsetTheme),
        )
    }

    @Test
    fun uniqueIdAndName_dedupe() {
        assertEquals("blank-skin", SkinCreator.uniqueId("blank-skin", emptySet()))
        assertEquals(
            "blank-skin-2",
            SkinCreator.uniqueId("blank-skin", setOf("blank-skin")),
        )
        assertEquals(
            "blank-skin-3",
            SkinCreator.uniqueId("blank-skin", setOf("blank-skin", "blank-skin-2")),
        )
        assertEquals("新建皮肤", SkinCreator.uniqueName("新建皮肤", emptySet()))
        assertEquals(
            "新建皮肤 2",
            SkinCreator.uniqueName("新建皮肤", setOf("新建皮肤")),
        )
    }

    @Test
    fun buildManifestJson_roundTrips() {
        val skinBytes = SkinCreator.buildSkinJson(
            "builtin-default", "默认 副本", defaultBoards,
        ).encodeToByteArray()
        val manifest = PackageManifest.parse(
            SkinCreator.buildManifestJson("builtin-default", skinBytes),
        )
        requireNotNull(manifest)
        assertEquals(SkinValidator.SUPPORTED_FORMAT_VERSION, manifest.formatVersion)
        assertEquals(1, manifest.contents.size)
        val content = manifest.contents.single()
        assertEquals(PackageManifest.TYPE_SKIN, content.type)
        assertEquals("builtin-default", content.id)
        assertEquals("skin.json", content.path)
        assertEquals(DigestUtil.sha256Hex(skinBytes), content.sha256)
        assertEquals(skinBytes.size.toLong(), content.sizeBytes)
        // 清单 skin 条目 id 与 skin.json meta.id 一致（导入门 skin_meta_id_mismatch）。
        assertEquals("builtin-default", manifest.skin()?.id)
    }

    @Test
    fun synthesizedSkin_passesValidation() {
        for (boards in listOf(
            defaultBoards,
            SkinCreator.Boards(
                light = monetLightScheme,
                dark = monetDarkScheme,
            ),
        )) {
            val tree = SkinJson.parseObject(
                SkinCreator.buildSkinJson("builtin-default", "默认 副本", boards),
            )
            requireNotNull(tree)
            val result = SkinValidator.validate(
                tree = tree,
                currentVersionCode = 216,
                fallbacks = GeometryFallbacks(8f, 2f, 2f, 4f),
                assetKind = { null },
            )
            assertTrue("synthesized skin rejected: $result", result is SkinValidator.Result.Valid)
        }
    }
}
