package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.layout.LayoutResolver
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全布局矩阵（2026-10-27 21:45 用户令）：双色板跟随系统、
 * 横屏专属布局、旋转重建加固在九宫格 / 26 键 / 十五键 /
 * 数字键盘 / 自定义 11/14/17/18 键布局上全部生效。
 * 旋转本身是设备行为，单元层覆盖方向选择逻辑（目标解析
 * + 生效布局装配）；真机旋转仍列真机验证项。
 */
class LayoutMatrixTest {

    private fun scheme(keyBackground: Int) = KeyboardColors.ColorScheme(
        keyBackground = keyBackground, keyPressed = 101, keyBorderStroke = 102,
        specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
        accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
        keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
        background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999, toastText = 998,
    )

    private val lightScheme = scheme(0xFFFDF6EC.toInt())
    private val darkScheme = scheme(0xFF232B28.toInt())

    private fun keyDef(id: String, label: String): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = label, textSize = 18f, percentWidth = 1f,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(label)),
        ),
        id = id,
    )

    private fun resolved(
        id: String, label: String, landscapeLabel: String? = null,
    ): ResolvedLayout {
        val base = listOf(listOf(keyDef("$id:K-1", label)))
        val landscapeBase = landscapeLabel?.let {
            listOf(listOf(keyDef("$id:K-1", it)))
        } ?: base
        return ResolvedLayout(
            layoutId = id, generation = 1, name = id,
            baseLayout = base, baseLayoutLandscape = landscapeBase,
        )
    }

    private fun snapshot(
        portrait: ResolvedLayout? = null,
        landscape: ResolvedLayout? = null,
    ): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = lightScheme,
        darkColorScheme = darkScheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = portrait?.layoutId,
        resolvedLayout = portrait,
        layoutResolution = if (portrait != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
        resolvedLayoutLandscape = landscape,
        landscapeLayoutResolution = if (landscape != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
    )

    // ---------- 主键盘位目标解析：横屏分支 ----------

    @Test
    fun landscapeOnlyBindingRedirectsMainSlotOnlyInLandscape() {
        val snap = snapshot(landscape = resolved("lay-ls", "l"))
        // 竖屏：无竖屏绑定，主键盘名恒原样（内置布局照常）
        for (main in listOf("Qwerty", "T9", "T15")) {
            assertEquals(
                main,
                CustomLayoutRuntime.resolveSwitchTarget(snap, main, "Qwerty"),
            )
        }
        // 横屏：主键盘名改指横屏绑定
        for (main in listOf("Qwerty", "T9", "T15")) {
            assertEquals(
                "custom:lay-ls",
                CustomLayoutRuntime.resolveSwitchTarget(
                    snap, main, "Qwerty", landscape = true,
                ),
            )
        }
        // 横屏下横屏绑定名本身成立；竖屏下它无效 → 回退方案默认
        assertEquals(
            "custom:lay-ls",
            CustomLayoutRuntime.resolveSwitchTarget(
                snap, "custom:lay-ls", "Qwerty", landscape = true,
            ),
        )
        assertEquals(
            "Qwerty",
            CustomLayoutRuntime.resolveSwitchTarget(snap, "custom:lay-ls", "Qwerty"),
        )
        // 场景/工具键盘两个方向都不改指
        for (name in listOf("Number", "Symbol", "Emoji")) {
            assertEquals(
                name,
                CustomLayoutRuntime.resolveSwitchTarget(snap, name, "Qwerty"),
            )
            assertEquals(
                name,
                CustomLayoutRuntime.resolveSwitchTarget(
                    snap, name, "Qwerty", landscape = true,
                ),
            )
        }
    }

    @Test
    fun portraitBindingWinsInLandscapeWhenBothBound() {
        // 两绑定并存时横屏仍走竖屏绑定名（横屏结构由装配层
        // 在该绑定内取横屏变体），不跳到横屏绑定名。
        val snap = snapshot(
            portrait = resolved("lay-p", "p"),
            landscape = resolved("lay-ls", "l"),
        )
        assertEquals(
            "custom:lay-p",
            CustomLayoutRuntime.resolveSwitchTarget(
                snap, "T9", "Qwerty", landscape = true,
            ),
        )
        assertEquals(
            "custom:lay-p",
            CustomLayoutRuntime.resolveSwitchTarget(snap, "T9", "Qwerty"),
        )
    }

    @Test
    fun portraitOnlyBindingRedirectsBothOrientations() {
        val snap = snapshot(portrait = resolved("lay-p", "p"))
        assertEquals(
            "custom:lay-p",
            CustomLayoutRuntime.resolveSwitchTarget(
                snap, "T9", "Qwerty", landscape = true,
            ),
        )
    }

    // ---------- 生效布局装配：方向选择 ----------

    @Test
    fun landscapeOnlyBindingServesLandscapeOnly() {
        val snap = snapshot(landscape = resolved("lay-ls", "l"))
        assertNull(CustomLayoutRuntime.effectiveLayout(snap, emptyMap()))
        val landscape = CustomLayoutRuntime.effectiveLayout(
            snap, emptyMap(), landscape = true,
        )
        assertNotNull(landscape)
        // 无覆写恒等纪律：横屏取绑定布局的横屏变体同一引用
        assertSame(
            snap.resolvedLayoutLandscape!!.baseLayoutLandscape, landscape,
        )
    }

    @Test
    fun bothBoundLandscapeTakesBindingVariant() {
        val snap = snapshot(
            portrait = resolved("lay-p", "p", "p-ls"),
            landscape = resolved("lay-ls", "l"),
        )
        val landscape = CustomLayoutRuntime.effectiveLayout(
            snap, emptyMap(), landscape = true,
        )!!
        assertSame(snap.resolvedLayoutLandscape!!.baseLayoutLandscape, landscape)
        assertEquals(
            "l",
            (landscape[0][0].appearance as KeyDef.Appearance.Text).displayText,
        )
        val portrait = CustomLayoutRuntime.effectiveLayout(snap, emptyMap())!!
        assertSame(snap.resolvedLayout!!.baseLayout, portrait)
    }

    @Test
    fun portraitOnlyLandscapeFallsBackToPortraitVariant() {
        val snap = snapshot(portrait = resolved("lay-p", "p", "p-ls"))
        val landscape = CustomLayoutRuntime.effectiveLayout(
            snap, emptyMap(), landscape = true,
        )!!
        assertSame(snap.resolvedLayout!!.baseLayoutLandscape, landscape)
        assertEquals(
            "p-ls",
            (landscape[0][0].appearance as KeyDef.Appearance.Text).displayText,
        )
    }

    @Test
    fun overrideHitCountUsesEffectiveOrientationIds() {
        val snap = snapshot(
            portrait = resolved("lay-p", "p"),
            landscape = resolved("lay-ls", "l"),
        ).copy(
            keyOverrides = mapOf(
                "lay-ls:K-1" to KeyOverride(label = "L"),
            ),
        )
        assertEquals(0, CustomLayoutRuntime.overrideHitCount(snap))
        assertEquals(1, CustomLayoutRuntime.overrideHitCount(snap, landscape = true))
    }

    // ---------- 内置布局：双色板令牌解析 ----------

    private fun builtinTokenFace(
        layout: List<List<KeyDef>>,
        scheme: KeyboardColors.ColorScheme,
    ): Int {
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        val snap = SkinSnapshot(
            ref = SkinGenRef("skin-1", "h", 1),
            name = "S",
            colorScheme = lightScheme,
            darkColorScheme = darkScheme,
            geometryPresence = emptySet(),
            effectiveGeometry = emptyMap(),
            background = null,
            degradations = emptySet(),
            layoutId = null,
            keyOverrides = mapOf(target!!.id!! to KeyOverride(styleRef = "tok")),
            keyStyles = mapOf(
                "tok" to mapOf(KeyStyleRole.FACE to "keyBackground"),
            ),
        )
        val result = KeyOverrideApplier.apply(
            "builtin", layout, snap, emptyMap(), scheme = scheme,
        )
        val styled = result.flatten().first { it.id == target.id }
        return styled.appearance.skinFaceColor!!
    }

    @Test
    fun builtinLayoutsResolveTokenToEffectivePalette() {
        val layouts = listOf(
            "Qwerty" to QwertyKeyboard.buildLayout(Variant.Accent),
            "T9" to T9Keyboard.buildLayout(Variant.Accent),
            "T15" to T15Keyboard.buildLayout(Variant.Accent),
            "Number" to NumberKeyboard.buildLayout(Variant.Accent),
        )
        for ((name, layout) in layouts) {
            assertEquals(
                "light palette for $name",
                0xFFFDF6EC.toInt(),
                builtinTokenFace(layout, lightScheme),
            )
            assertEquals(
                "dark palette for $name",
                0xFF232B28.toInt(),
                builtinTokenFace(layout, darkScheme),
            )
        }
    }

    // ---------- 自定义布局键数：11/14/17/18 ----------

    private fun countLayoutJson(keyCount: Int) = buildJsonObject {
        put("formatVersion", 3)
        put("meta", buildJsonObject {
            put("id", "lay-$keyCount")
            put("name", "lay-$keyCount")
        })
        put("rows", buildJsonArray {
            var remaining = keyCount
            var index = 0
            while (remaining > 0) {
                val rowSize = minOf(12, remaining)
                add(buildJsonObject {
                    put("keys", buildJsonArray {
                        repeat(rowSize) {
                            index += 1
                            add(buildJsonObject {
                                put("stableId", "K-$index")
                                put("label", "k$index")
                                put("widthWeight", 1.0)
                                put("landscapeWidthWeight", 2.0)
                                put("tap", buildJsonObject {
                                    put("type", "text")
                                    put("value", "k$index")
                                })
                            })
                        }
                    })
                })
                remaining -= rowSize
            }
        })
    }

    @Test
    fun customKeyCountsResolveWithLandscapeVariants() {
        for (count in listOf(11, 14, 17, 18)) {
            val outcome = LayoutResolver.resolve(
                countLayoutJson(count), "lay-$count", 1, emptyMap(),
            )
            assertTrue(
                "count $count must resolve, got $outcome",
                outcome is LayoutResolver.Outcome.Resolved,
            )
            val resolved = (outcome as LayoutResolver.Outcome.Resolved).resolved
            // 实键数（2026-10-29 item-1 D4：短行补的空白键 id 为 null，不计入）
            assertEquals(count, resolved.baseLayout.flatten().count { it.id != null })
            assertEquals(count, resolved.baseLayoutLandscape.flatten().count { it.id != null })
            // 横屏变体键集与竖屏一致（仅权重不同）
            assertEquals(
                resolved.baseLayout.flatten().mapNotNull { it.id },
                resolved.baseLayoutLandscape.flatten().mapNotNull { it.id },
            )
        }
    }

    // ---------- 快照双板：旧单板皮肤兼容（矩阵视角） ----------

    @Test
    fun legacySinglePaletteEffectiveIsIdenticalBothModes() {
        val snap = SkinSnapshot(
            ref = SkinGenRef("skin-1", "h", 1),
            name = "S",
            colorScheme = lightScheme,
            geometryPresence = emptySet(),
            effectiveGeometry = emptyMap(),
            background = null,
            degradations = emptySet(),
            layoutId = null,
        )
        assertNull(snap.darkColorScheme)
        assertSame(lightScheme, snap.effectiveColorScheme(false))
        assertSame(lightScheme, snap.effectiveColorScheme(true))
    }
}
