package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证渲染链的内容层回归（根因调查，2026-10-27）：
 * 交付的 validation-skin.jime / skin.json 经校验器 → 快照构建器 →
 * T9 覆写装配器，配色与 123 键上滑 Symbol 必须完整到达运行态。
 * 本测试只覆盖 JVM 可验证的内容层；窗口刷新投递（Android
 * View.post / onDraw）不在此列，需真机判据另行验证。
 */
class ValidationRenderChainTest {

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
    )

    private fun validationSnapshot() = run {
        val bytes = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("validation-skin.json")?.readBytes()
            ?: error("missing test resource validation-skin.json")
        val tree = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val result = SkinValidator.validate(
            tree = tree,
            currentVersionCode = 157,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
            assetKind = { null },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val validated = (result as SkinValidator.Result.Valid).skin
        SkinSnapshotBuilder.build(
            validated = validated,
            ref = SkinGenRef("validation-skin", "0".repeat(64), 1),
            defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL,
            assetSha256 = { null },
            targetWidthPx = 100,
            targetHeightPx = 50,
        )
    }

    @Test
    fun validationSnapshotCarriesVisibleColors() {
        val snapshot = validationSnapshot()
        val scheme = snapshot.colorScheme
        // 窗口底 / 候选条底：灰 #D1D1D6（与内置底色不同）
        assertEquals(0xFFD1D1D6.toInt(), scheme.background)
        assertEquals(0xFFD1D1D6.toInt(), scheme.panel.background)
        assertEquals(0xFFD1D1D6.toInt(), scheme.panel.candidateBackground)
        // 工具栏激活金 #9C824A
        assertEquals(0xFF9C824A.toInt(), scheme.accentKeyBackground)
        assertEquals(0xFF9C824A.toInt(), scheme.panel.toolbarActived)
        // 按键描边透明：有 hairline = 仍是内置，无 = Bin 已应用
        assertEquals(0x00000000, scheme.keyBorderStroke)
        assertEquals(0x00000000, scheme.specialKeyBorderStroke)
        assertEquals(KeyboardColors.SurfaceStyle.Flat, scheme.surfaceStyle)
        // 白键
        assertEquals(0xFFFFFFFF.toInt(), scheme.keyBackground)
        assertEquals(5, snapshot.keyOverrides.size)
    }

    @Test
    fun validationT9NumberKeySwipeUpBecomesSymbolAndTapUnchanged() {
        val snapshot = validationSnapshot()
        val factory = T9Keyboard.buildLayout(Variant.Normal)
        val applied = KeyOverrideApplier.apply("T9", factory, snapshot, emptyMap())
        assertNotSame(factory, applied)
        val key = applied.flatten().first { it.id == "T9_LAYOUT_NUMBER" }
        val swipe = key.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
        assertEquals(
            KeyboardAction.LayoutSwitchAction("Symbol"),
            swipe?.upAction,
        )
        assertEquals(
            KeyboardAction.LayoutSwitchAction("Emoji"),
            swipe?.downAction,
        )
        val press = key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()
        assertEquals(
            KeyboardAction.LayoutSwitchAction("Number"),
            press?.action,
        )
        // 出厂 def 不被原地修改
        val factoryKey = factory.flatten().first { it.id == "T9_LAYOUT_NUMBER" }
        val factorySwipe = factoryKey.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>()
            .firstOrNull()
        assertTrue(
            "factory swipe must not gain Symbol up-action",
            factorySwipe?.upAction != KeyboardAction.LayoutSwitchAction("Symbol"),
        )
        // 未命中覆写的键原样透传
        val plain = applied.flatten().first { it.id == "T9_KEY_2" }
        val factoryPlain = factory.flatten().first { it.id == "T9_KEY_2" }
        assertSame(factoryPlain, plain)
    }
}
