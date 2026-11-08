// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.notify.NotificationSource
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆写装配器通知叠加（接线轮 R1）＋条件门控（R2）：样式名命中、状态层优先。 */
class KeyOverrideNotificationTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
        toastBackground = 40, toastText = 41,
    )

    private fun snapshot() = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = emptyMap(),
        soundBindings = SoundBindings.EMPTY,
        keyStyles = mapOf(
            "accent" to mapOf(
                KeyStyleRole.FACE to "#FF0000",
                KeyStyleRole.TEXT to "#00FF00",
            ),
        ),
    )

    private fun accentedLayout(): List<List<KeyDef>> {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        layout[0][0].skinStyleRef = "accent"
        return layout
    }

    private fun KeyDef.pressAction(): KeyboardAction? =
        behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()?.action

    private fun KeyDef.swipeUp(): KeyboardAction? =
        behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()?.upAction

    @Test
    fun latchedNotification_overridesStyleColorAndActions() {
        val latched = SkinNotification(
            source = NotificationSource.RIME_OPTION, matchKey = "ascii_mode",
            matchValue = "true", targetStyle = "accent",
            backgroundStyle = "#0000FF", foregroundStyle = null,
            action = SkinAction.Command(WhitelistAction.CAPS),
            swipeUpAction = SkinAction.Command(WhitelistAction.UNDO),
            swipeDownAction = null,
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty", accentedLayout(), snapshot(), emptyMap(),
            scheme = scheme, notifications = mapOf("accent" to latched),
        )
        val key = result[0][0]
        // 通知背景色优先于样式表红色
        assertEquals(0xFF0000FF.toInt(), key.appearance.skinFaceColor)
        // 样式表文字色（通知未声明前景）保留
        assertEquals(0xFF00FF00.toInt(), key.appearance.skinTextColor)
        assertEquals(KeyboardAction.CapsAction, key.pressAction())
        assertEquals(KeyboardAction.UndoAction, key.swipeUp())
    }

    @Test
    fun gatedStyle_inactiveSuppressesActiveRestores() {
        val snap = snapshot()
        val layout = accentedLayout()
        val inactiveAgain = KeyOverrideApplier.apply(
            "Qwerty", layout, snap, emptyMap(),
            scheme = scheme, gatedStyleNames = setOf("accent"),
            activeStyleNames = emptySet(),
        )
        // 门控未激活：样式不生效，键原样返回（无复制、无面色）
        assertSame(layout[0][0], inactiveAgain[0][0])
        assertNull(inactiveAgain[0][0].appearance.skinFaceColor)

        val active = KeyOverrideApplier.apply(
            "Qwerty", accentedLayout(), snap, emptyMap(),
            scheme = scheme, gatedStyleNames = setOf("accent"),
            activeStyleNames = setOf("accent"),
        )
        assertEquals(0xFFFF0000.toInt(), active[0][0].appearance.skinFaceColor)
    }

    @Test
    fun unlatchedStyle_unaffected() {
        val result = KeyOverrideApplier.apply(
            "Qwerty", accentedLayout(), snapshot(), emptyMap(),
            scheme = scheme, notifications = mapOf("other" to SkinNotification(
                source = NotificationSource.SCHEME, matchKey = null, matchValue = null,
                targetStyle = "other", backgroundStyle = "#0000FF",
                foregroundStyle = null, action = null,
                swipeUpAction = null, swipeDownAction = null,
            )),
        )
        // 通知目标不是本键样式名：样式表红色照常生效
        assertEquals(0xFFFF0000.toInt(), result[0][0].appearance.skinFaceColor)
        assertTrue(result[0][0].pressAction() !is KeyboardAction.CapsAction)
    }

    // ---- 组字态通知 label（Bin-2 diff #3）：回车“发送”→“确认” ----

    /** 单键文本回车（源包 enterButton 形态）：出厂文案“发送”。 */
    private fun enterLayout(): List<List<KeyDef>> = listOf(
        listOf(
            KeyDef(
                appearance = KeyDef.Appearance.Text(displayText = "发送", textSize = 16f),
                behaviors = setOf(
                    KeyDef.Behavior.Press(KeyboardAction.ReturnAction()),
                ),
                id = "enter",
            ),
        ),
    )

    private fun enterSnapshot(override: KeyOverride) = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = mapOf("enter" to override),
        soundBindings = SoundBindings.EMPTY,
        keyStyles = mapOf(
            "enterStyle" to mapOf(
                KeyStyleRole.FACE to "#FF0000",
                KeyStyleRole.TEXT to "#00FF00",
            ),
        ),
    )

    private fun KeyDef.displayText(): String = when (val a = appearance) {
        is KeyDef.Appearance.Text -> a.displayText
        is KeyDef.Appearance.AltText -> a.displayText
        is KeyDef.Appearance.ImageText -> a.displayText
        else -> ""
    }

    private fun preeditLabelNotification(label: String) = SkinNotification(
        source = NotificationSource.PREEDIT, matchKey = null, matchValue = "nonempty",
        targetStyle = "enterStyle", backgroundStyle = null, foregroundStyle = null,
        action = null, swipeUpAction = null, swipeDownAction = null, label = label,
    )

    @Test
    fun latchedLabel_overridesDynamicTextAndFreezesRefresh() {
        val override = KeyOverride(
            label = "发送",
            styleRef = "enterStyle",
            dynamicText = DynamicTextSource.RETURN_KEY_TYPE,
        )
        val result = KeyOverrideApplier.apply(
            "T9", enterLayout(), enterSnapshot(override), emptyMap(),
            scheme = scheme,
            dynamicTexts = mapOf(DynamicTextSource.RETURN_KEY_TYPE to "搜索"),
            notifications = mapOf("enterStyle" to preeditLabelNotification("确认")),
        )
        val key = result[0][0]
        // 锁定 label 优先于动态值：组字态回车显示“确认”
        assertEquals("确认", key.displayText())
        // 冻结：不注册动态刷新，下一轮 pushDynamicTexts 不会盖回“搜索”
        assertNull(key.appearance.skinDynamicText)
        // 生效样式名随副本保留（运行层凭它认出本键的锁定通知）
        assertEquals("enterStyle", key.skinStyleRef)
    }

    @Test
    fun unlatched_dynamicTextPreserved() {
        val override = KeyOverride(
            label = "发送",
            styleRef = "enterStyle",
            dynamicText = DynamicTextSource.RETURN_KEY_TYPE,
        )
        val result = KeyOverrideApplier.apply(
            "T9", enterLayout(), enterSnapshot(override), emptyMap(),
            scheme = scheme,
            dynamicTexts = mapOf(DynamicTextSource.RETURN_KEY_TYPE to "搜索"),
            notifications = emptyMap(),
        )
        val key = result[0][0]
        // 无锁定时动态值照常（回车跟 returnKeyType）
        assertEquals("搜索", key.displayText())
        assertEquals("returnKeyType", key.appearance.skinDynamicText)
        assertEquals("发送", key.appearance.skinDynamicFallback)
    }

    @Test
    fun latchedLabel_overridesStaticLabel() {
        // 静态键形态（t91“@/. ”→“分词”、clearPreedit“换行”→“重输”同款）：
        // 无动态来源，锁定 label 直接盖静态 label。
        val override = KeyOverride(label = "发送", styleRef = "enterStyle")
        val latched = KeyOverrideApplier.apply(
            "T9", enterLayout(), enterSnapshot(override), emptyMap(),
            scheme = scheme,
            notifications = mapOf("enterStyle" to preeditLabelNotification("确认")),
        )
        assertEquals("确认", latched[0][0].displayText())
        val unlatched = KeyOverrideApplier.apply(
            "T9", enterLayout(), enterSnapshot(override), emptyMap(),
            scheme = scheme, notifications = emptyMap(),
        )
        assertEquals("发送", unlatched[0][0].displayText())
    }

    @Test
    fun assembledDef_carriesFactoryStyleRefWithoutOverride() {
        // 无覆写时沿用出厂引用（旧行为不变）。
        val result = KeyOverrideApplier.apply(
            "Qwerty", accentedLayout(), snapshot(), emptyMap(), scheme = scheme,
        )
        assertEquals("accent", result[0][0].skinStyleRef)
    }
}
