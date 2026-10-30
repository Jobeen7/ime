// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.panel.toolbar.CustomButtonSlot
import com.jobeen.ime.input.panel.toolbar.CustomDisplayKind
import com.jobeen.ime.input.panel.toolbar.EffectiveToolbarButton
import com.jobeen.ime.input.panel.toolbar.ToolbarActionBinding
import com.jobeen.ime.input.panel.toolbar.ToolbarBackground
import com.jobeen.ime.input.panel.toolbar.ToolbarButtonEntry
import com.jobeen.ime.input.panel.toolbar.ToolbarCustomButton
import com.jobeen.ime.input.panel.toolbar.ToolbarCustomConfig
import com.jobeen.ime.input.panel.toolbar.ToolbarSkinState
import com.jobeen.ime.input.panel.toolbar.ToolbarUserData
import com.jobeen.ime.input.panel.toolbar.ToolbarUserStore
import com.jobeen.ime.skin.model.WhitelistAction
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏用户覆盖层：校验／抉择／持久化往返（纯逻辑，不碰 Android 框架）。
 */
class ToolbarUserStoreTest {

    private fun tapBinding(action: WhitelistAction, value: String? = null) =
        ToolbarActionBinding(action, value)

    private fun customButton(
        id: String = "custom:abcd1234",
        kind: CustomDisplayKind = CustomDisplayKind.TEXT,
        display: String = "常用",
        imageFile: String? = null,
        iconSizeDp: Int = 24,
        widthDp: Int = 48,
        fontSizeSp: Float? = null,
        borderArgb: Int? = null,
        borderWidthDp: Float = 0f,
        pressedArgb: Int? = null,
        bindings: Map<CustomButtonSlot, ToolbarActionBinding> = mapOf(
            CustomButtonSlot.TAP to tapBinding(WhitelistAction.COMMIT_DIRECT, "谢谢"),
        ),
    ) = ToolbarCustomButton(
        id = id,
        kind = kind,
        display = display,
        imageFile = imageFile,
        iconSizeDp = iconSizeDp,
        widthDp = widthDp,
        backgroundArgb = null,
        bindings = bindings,
        fontSizeSp = fontSizeSp,
        borderArgb = borderArgb,
        borderWidthDp = borderWidthDp,
        pressedArgb = pressedArgb,
    )

    @Test
    fun validateCustomButton_ok() {
        assertTrue(ToolbarUserStore.validateCustomButton(customButton()).isEmpty())
    }

    @Test
    fun validateCustomButton_textBlank_rejected() {
        val problems = ToolbarUserStore.validateCustomButton(customButton(display = "  "))
        assertTrue(problems.any { it.contains("显示文字") })
    }

    @Test
    fun validateCustomButton_imageTraversal_rejected() {
        val problems = ToolbarUserStore.validateCustomButton(
            customButton(kind = CustomDisplayKind.IMAGE, display = "", imageFile = "../evil.png"),
        )
        assertTrue(problems.any { it.contains("图片文件") })
    }

    @Test
    fun validateCustomButton_imageSubdir_rejected() {
        val problems = ToolbarUserStore.validateCustomButton(
            customButton(kind = CustomDisplayKind.IMAGE, display = "", imageFile = "sub/pic.png"),
        )
        assertTrue(problems.any { it.contains("图片文件") })
    }

    @Test
    fun validateCustomButton_sizeRange() {
        assertTrue(
            ToolbarUserStore.validateCustomButton(customButton(iconSizeDp = 15)).isNotEmpty(),
        )
        assertTrue(
            ToolbarUserStore.validateCustomButton(customButton(iconSizeDp = 49)).isNotEmpty(),
        )
        assertTrue(
            ToolbarUserStore.validateCustomButton(customButton(widthDp = 23)).isNotEmpty(),
        )
        assertTrue(
            ToolbarUserStore.validateCustomButton(customButton(widthDp = 161)).isNotEmpty(),
        )
        // 宽度上限已随参考范围改为 160：160 本身合法。
        assertTrue(
            ToolbarUserStore.validateCustomButton(customButton(widthDp = 160)).isEmpty(),
        )
    }

    @Test
    fun validateBinding_voiceInput_onlyLongPress() {
        val (clean, problem) = ToolbarUserStore.validateBinding(
            CustomButtonSlot.TAP,
            tapBinding(WhitelistAction.VOICE_INPUT),
        )
        assertNull(clean)
        assertNotNull(problem)
        val (cleanLong, problemLong) = ToolbarUserStore.validateBinding(
            CustomButtonSlot.LONG_PRESS,
            tapBinding(WhitelistAction.VOICE_INPUT),
        )
        assertNotNull(cleanLong)
        assertNull(problemLong)
    }

    @Test
    fun validateBinding_valueRules() {
        // 必填值缺失拒绝，带值通过且去空白。
        val (cleanMissing, problemMissing) = ToolbarUserStore.validateBinding(
            CustomButtonSlot.TAP,
            tapBinding(WhitelistAction.COMMIT_DIRECT, "  "),
        )
        assertNull(cleanMissing)
        assertNotNull(problemMissing)
        val (cleanValue, _) = ToolbarUserStore.validateBinding(
            CustomButtonSlot.TAP,
            tapBinding(WhitelistAction.COMMIT_DIRECT, " 谢谢 "),
        )
        assertEquals("谢谢", cleanValue?.value)
        // 禁值动作的值被归一化为 null。
        val (cleanForbidden, _) = ToolbarUserStore.validateBinding(
            CustomButtonSlot.TAP,
            tapBinding(WhitelistAction.DELETE, "多余"),
        )
        assertNull(cleanForbidden?.value)
    }

    @Test
    fun validateButtons_capAndDuplicates() {
        val nine = (1..9).map { ToolbarButtonEntry("emoji") }
        // 9 个同 id 已触重复；先验上限：10 项必超上限。
        val ten = List(10) { ToolbarButtonEntry("emoji") }
        assertTrue(ToolbarUserStore.validateButtons(ten, emptyMap()).any { it.contains("超上限") })
        val dup = listOf(ToolbarButtonEntry("emoji"), ToolbarButtonEntry("emoji"))
        assertTrue(ToolbarUserStore.validateButtons(dup, emptyMap()).any { it.contains("重复") })
        val unknown = listOf(ToolbarButtonEntry("no_such_button"))
        assertTrue(ToolbarUserStore.validateButtons(unknown, emptyMap()).any { it.contains("未知") })
        val missing = listOf(ToolbarButtonEntry("custom:gone"))
        assertTrue(
            ToolbarUserStore.validateButtons(missing, emptyMap()).any { it.contains("缺失") },
        )
        assertTrue(nine.size == 9)
    }

    @Test
    fun validateButtons_poolAndCustom_ok() {
        val buttons = listOf(
            ToolbarButtonEntry("emoji"),
            ToolbarButtonEntry("custom:abcd1234"),
        )
        assertTrue(
            ToolbarUserStore.validateButtons(buttons, mapOf("custom:abcd1234" to customButton()))
                .isEmpty(),
        )
    }

    @Test
    fun resolve_noState_returnsNull() {
        assertNull(ToolbarUserStore.resolve("skin-a", ToolbarUserData()))
    }

    @Test
    fun resolve_emptyList_returnsNull() {
        val data = ToolbarUserData(skins = mapOf("skin-a" to ToolbarSkinState(emptyList())))
        assertNull(ToolbarUserStore.resolve("skin-a", data))
    }

    @Test
    fun resolve_allDisabled_returnsNull() {
        val data = ToolbarUserData(
            skins = mapOf(
                "skin-a" to ToolbarSkinState(
                    listOf(ToolbarButtonEntry("emoji", enabled = false)),
                ),
            ),
        )
        assertNull(ToolbarUserStore.resolve("skin-a", data))
    }

    @Test
    fun resolve_perSkinIsolation() {
        val data = ToolbarUserData(
            skins = mapOf(
                "skin-a" to ToolbarSkinState(listOf(ToolbarButtonEntry("emoji"))),
                "skin-b" to ToolbarSkinState(listOf(ToolbarButtonEntry("copy"))),
            ),
        )
        val a = ToolbarUserStore.resolve("skin-a", data)!!
        val b = ToolbarUserStore.resolve("skin-b", data)!!
        assertEquals(1, a.buttons.size)
        assertTrue(a.buttons[0] is EffectiveToolbarButton.Pool)
        assertEquals("emoji", (a.buttons[0] as EffectiveToolbarButton.Pool).id)
        assertEquals("copy", (b.buttons[0] as EffectiveToolbarButton.Pool).id)
    }

    @Test
    fun resolve_customToolbarWinsOverList() {
        val def = customButton()
        val config = ToolbarCustomConfig(
            id = "toolbar:11111111",
            name = "我的栏",
            buttons = listOf(ToolbarButtonEntry("custom:abcd1234")),
            background = ToolbarBackground.Transparent,
        )
        val data = ToolbarUserData(
            skins = mapOf(
                "skin-a" to ToolbarSkinState(
                    buttons = listOf(ToolbarButtonEntry("emoji")),
                    activeToolbarId = "toolbar:11111111",
                ),
            ),
            customButtons = mapOf(def.id to def),
            customToolbars = mapOf(config.id to config),
        )
        val resolved = ToolbarUserStore.resolve("skin-a", data)!!
        assertEquals(1, resolved.buttons.size)
        assertTrue(resolved.buttons[0] is EffectiveToolbarButton.Custom)
        assertTrue(resolved.background is ToolbarBackground.Transparent)
    }

    @Test
    fun resolve_danglingActiveToolbar_returnsNull() {
        val data = ToolbarUserData(
            skins = mapOf(
                "skin-a" to ToolbarSkinState(
                    buttons = listOf(ToolbarButtonEntry("emoji")),
                    activeToolbarId = "toolbar:gone",
                ),
            ),
        )
        assertNull(ToolbarUserStore.resolve("skin-a", data))
    }

    @Test
    fun resolve_danglingRefsSkipped() {
        val data = ToolbarUserData(
            skins = mapOf(
                "skin-a" to ToolbarSkinState(
                    listOf(
                        ToolbarButtonEntry("custom:gone"),
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("no_such"),
                    ),
                ),
            ),
        )
        val resolved = ToolbarUserStore.resolve("skin-a", data)!!
        assertEquals(1, resolved.buttons.size)
        assertEquals("emoji", (resolved.buttons[0] as EffectiveToolbarButton.Pool).id)
    }

    @Test
    fun persistence_roundTrip() {
        val dir = Files.createTempDirectory("toolbar-user-test").toFile()
        try {
            val file = File(dir, ToolbarUserStore.FILE_NAME)
            val def = customButton()
            val config = ToolbarCustomConfig(
                id = "toolbar:22222222",
                name = "夜栏",
                buttons = listOf(ToolbarButtonEntry(def.id)),
                background = ToolbarBackground.Color(0xFF1E88E5.toInt()),
            )
            val data = ToolbarUserData(
                skins = mapOf(
                    "skin-a" to ToolbarSkinState(
                        buttons = listOf(
                            ToolbarButtonEntry("emoji"),
                            ToolbarButtonEntry(def.id, enabled = false),
                        ),
                        activeToolbarId = "toolbar:22222222",
                    ),
                ),
                customButtons = mapOf(def.id to def),
                customToolbars = mapOf(config.id to config),
            )
            assertTrue(ToolbarUserStore.save(file, data))
            val loaded = ToolbarUserStore.load(file)
            assertFalse(loaded.corrupt)
            assertEquals(1, loaded.data.skins.size)
            assertEquals("toolbar:22222222", loaded.data.skins["skin-a"]?.activeToolbarId)
            assertEquals(2, loaded.data.skins["skin-a"]?.buttons?.size)
            assertEquals(def, loaded.data.customButtons[def.id])
            assertEquals(config, loaded.data.customToolbars[config.id])
            // 抉择在往返后一致（生效栏胜出）。
            val resolved = ToolbarUserStore.resolve("skin-a", loaded.data)!!
            assertEquals(1, resolved.buttons.size)
            assertTrue(resolved.background is ToolbarBackground.Color)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun load_missingFile_emptyNotCorrupt() {
        val dir = Files.createTempDirectory("toolbar-user-missing").toFile()
        try {
            val loaded = ToolbarUserStore.load(File(dir, ToolbarUserStore.FILE_NAME))
            assertFalse(loaded.corrupt)
            assertTrue(loaded.data.skins.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun load_corruptFile_emptyAndFlagged() {
        val dir = Files.createTempDirectory("toolbar-user-corrupt").toFile()
        try {
            val file = File(dir, ToolbarUserStore.FILE_NAME)
            file.writeText("{不是 json")
            val loaded = ToolbarUserStore.load(file)
            assertTrue(loaded.corrupt)
            assertNotNull(loaded.errorClass)
            assertTrue(loaded.data.skins.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sanitizeImageFile_rules() {
        assertEquals("pic.png", ToolbarUserStore.sanitizeImageFile("pic.png"))
        assertNull(ToolbarUserStore.sanitizeImageFile("../evil.png"))
        assertNull(ToolbarUserStore.sanitizeImageFile("sub/pic.png"))
        assertNull(ToolbarUserStore.sanitizeImageFile("a\\b.png"))
        assertNull(ToolbarUserStore.sanitizeImageFile("  "))
        assertNull(ToolbarUserStore.sanitizeImageFile(null))
    }

    @Test
    fun newIds_havePrefixes() {
        assertTrue(ToolbarUserStore.newCustomButtonId().startsWith("custom:"))
        assertTrue(ToolbarUserStore.newCustomToolbarId().startsWith("toolbar:"))
    }

    @Test
    fun validateCustomButton_appearanceRanges() {
        // 字号超范围拒绝，边界通过；边框粗细超范围拒绝，0 与 4 通过。
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(fontSizeSp = 9f)).isNotEmpty())
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(fontSizeSp = 33f)).isNotEmpty())
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(fontSizeSp = 14f)).isEmpty())
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(borderWidthDp = -1f)).isNotEmpty())
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(borderWidthDp = 5f)).isNotEmpty())
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(borderWidthDp = 0f)).isEmpty())
        assertTrue(ToolbarUserStore.validateCustomButton(customButton(borderWidthDp = 4f)).isEmpty())
    }

    @Test
    fun buildCustomButton_ok_carriesAppearance() {
        val (def, problems) = ToolbarUserStore.buildCustomButton(
            id = "custom:abcd1234",
            kind = CustomDisplayKind.TEXT,
            display = "常用",
            imageFile = null,
            iconSizeDp = 24,
            widthDp = 48,
            backgroundArgb = 0xFFEEEEEE.toInt(),
            fontSizeSp = 16f,
            borderArgb = 0xFF9E9E9E.toInt(),
            borderWidthDp = 2f,
            pressedArgb = 0xFFCCCCCC.toInt(),
            bindings = mapOf(CustomButtonSlot.TAP to tapBinding(WhitelistAction.COMMIT_DIRECT, "谢谢")),
        )
        assertTrue(problems.isEmpty())
        assertNotNull(def)
        assertEquals(16f, def!!.fontSizeSp)
        assertEquals(0xFF9E9E9E.toInt(), def.borderArgb)
        assertEquals(2f, def.borderWidthDp)
        assertEquals(0xFFCCCCCC.toInt(), def.pressedArgb)
        assertEquals(16f, def.effectiveTextSizeDp())
    }

    @Test
    fun buildCustomButton_invalidBinding_returnsNullWithProblems() {
        val (def, problems) = ToolbarUserStore.buildCustomButton(
            id = "custom:abcd1234",
            kind = CustomDisplayKind.TEXT,
            display = "常用",
            imageFile = null,
            iconSizeDp = 24,
            widthDp = 48,
            backgroundArgb = null,
            fontSizeSp = null,
            borderArgb = null,
            borderWidthDp = 0f,
            pressedArgb = null,
            bindings = mapOf(CustomButtonSlot.TAP to tapBinding(WhitelistAction.VOICE_INPUT)),
        )
        assertNull(def)
        assertTrue(problems.isNotEmpty())
    }

    @Test
    fun effectiveTextSizeDp_followsIconWhenFontNull() {
        assertEquals(24f, customButton().effectiveTextSizeDp())
        assertEquals(18f, customButton(fontSizeSp = 18f).effectiveTextSizeDp())
    }

    @Test
    fun toolbarAddRows_orderAndAddedFlags() {
        val list = listOf(
            ToolbarButtonEntry("emoji"),
            ToolbarButtonEntry("custom:abcd1234"),
        )
        val customs = mapOf(
            "custom:abcd1234" to customButton(),
            "custom:eeee5678" to customButton(id = "custom:eeee5678", display = "再见"),
        )
        val rows = ToolbarUserStore.toolbarAddRows(list, customs)
        // 首行新建，其后池行（emoji 已添加标灰），最后未进列表的自定义行。
        assertTrue(rows[0] is ToolbarUserStore.ToolbarAddRow.NewCustom)
        val pool = rows.filterIsInstance<ToolbarUserStore.ToolbarAddRow.Pool>()
        assertEquals(12, pool.size)
        assertEquals("emoji", pool[0].id)
        assertEquals("表情", pool[0].label)
        assertTrue(pool[0].added)
        assertFalse(pool[1].added)
        val customRows = rows.filterIsInstance<ToolbarUserStore.ToolbarAddRow.Custom>()
        assertEquals(1, customRows.size)
        assertEquals("custom:eeee5678", customRows[0].id)
        assertFalse(customRows[0].isImage)
    }

    @Test
    fun jsonRoundTrip_appearanceFields() {
        val def = customButton(
            fontSizeSp = 16f,
            borderArgb = 0xFF9E9E9E.toInt(),
            borderWidthDp = 2f,
            pressedArgb = 0xFFCCCCCC.toInt(),
        )
        val dir = Files.createTempDirectory("toolbar-user-appearance").toFile()
        try {
            val file = File(dir, ToolbarUserStore.FILE_NAME)
            assertTrue(
                ToolbarUserStore.save(file, ToolbarUserData(customButtons = mapOf(def.id to def))),
            )
            val loaded = ToolbarUserStore.load(file)
            assertFalse(loaded.corrupt)
            assertEquals(def, loaded.data.customButtons[def.id])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun jsonLoad_oldFileWithoutAppearanceFields_defaults() {
        // 手写旧格式 JSON（无新字段）→ 默认值（字号跟随、无线框、按下默认），校验通过。
        val dir = Files.createTempDirectory("toolbar-user-oldfmt").toFile()
        try {
            val file = File(dir, ToolbarUserStore.FILE_NAME)
            file.writeText(
                """{"version":1,"skins":{},"customButtons":{"custom:abcd1234":{""" +
                    """"kind":"text","display":"常用","iconSizeDp":24,"widthDp":48,"bindings":{}}},"customToolbars":{}}""",
            )
            val loaded = ToolbarUserStore.load(file)
            assertFalse(loaded.corrupt)
            val def = loaded.data.customButtons["custom:abcd1234"]
            assertNotNull(def)
            assertNull(def!!.fontSizeSp)
            assertNull(def.borderArgb)
            assertEquals(0f, def.borderWidthDp)
            assertNull(def.pressedArgb)
            assertEquals(24f, def.effectiveTextSizeDp())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hideToolbar_roundTrip_truePersists() {
        val dir = Files.createTempDirectory("toolbar-user-hide").toFile()
        try {
            val file = File(dir, ToolbarUserStore.FILE_NAME)
            assertTrue(ToolbarUserStore.save(file, ToolbarUserData(hideToolbar = true)))
            val loaded = ToolbarUserStore.load(file)
            assertFalse(loaded.corrupt)
            assertTrue(loaded.data.hideToolbar)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hideToolbar_oldFileWithoutField_defaultsFalse() {
        val dir = Files.createTempDirectory("toolbar-user-hide-old").toFile()
        try {
            val file = File(dir, ToolbarUserStore.FILE_NAME)
            file.writeText("""{"version":1,"skins":{},"customButtons":{},"customToolbars":{}}""")
            val loaded = ToolbarUserStore.load(file)
            assertFalse(loaded.corrupt)
            assertFalse(loaded.data.hideToolbar)
        } finally {
            dir.deleteRecursively()
        }
    }
}
