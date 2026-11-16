package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.notify.NotificationSource
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * apply 结果缓存（2026-11-16 性能 ③）：
 *
 * - 同输入二次调用返回同一实例（工厂布局是每次新建的 KeyDef 实例，
 *   命中靠内容指纹，不是引用相等——本测试每调都新建布局与快照，
 *   故意不用同一实例）；
 * - 任一输出影响输入变化（方案板／布局内容／通知表）即不命中；
 * - 容量 16 条 LRU；
 * - 无触发项的早退路径不入缓存。
 *
 * 缓存是 object 级全局，每例先清——既有 KeyOverrideApplierTest
 * 各例不依赖缓存（同输入必同输出，纯函数），全量套件会复核。
 */
class KeyOverrideApplierCacheTest {

    @Before
    fun clear() {
        KeyOverrideApplier.clearApplyCache()
    }

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

    private val noPlaceholders = emptyMap<String, String>()

    private fun snapshot(
        overrides: Map<String, KeyOverride> = emptyMap(),
        refId: String = "skin-1",
    ) = SkinSnapshot(
        ref = SkinGenRef(refId, "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = overrides,
        soundBindings = SoundBindings.EMPTY,
    )

    /** 单行单键布局：每次调用都新建 KeyDef 实例（模拟生产重建）。 */
    private fun layout(label: String = "Q"): List<List<KeyDef>> = listOf(
        listOf(
            KeyDef(
                appearance = KeyDef.Appearance.Text(displayText = label, textSize = 22f),
                behaviors = setOf(
                    KeyDef.Behavior.Press(
                        KeyboardAction.KeySequenceAction(label.lowercase()),
                    ),
                ),
                id = "X:K-q",
            ),
        ),
    )

    private fun subLabelOverrides(sub: String = "x") =
        mapOf("X:K-q" to KeyOverride(subLabel = sub))

    @Test
    fun sameInputsTwice_returnsSameInstance() {
        val first = KeyOverrideApplier.apply(
            "X", layout(), snapshot(subLabelOverrides()), noPlaceholders,
        )
        // 布局与快照都重新建（内容相等、实例不同）：命中靠指纹＋快照结构相等。
        val second = KeyOverrideApplier.apply(
            "X", layout(), snapshot(subLabelOverrides()), noPlaceholders,
        )
        assertSame(first, second)
        assertEquals(1, KeyOverrideApplier.applyCacheSize())
    }

    @Test
    fun differentScheme_misses() {
        val first = KeyOverrideApplier.apply(
            "X", layout(), snapshot(subLabelOverrides()), noPlaceholders,
        )
        val second = KeyOverrideApplier.apply(
            "X", layout(), snapshot(subLabelOverrides()), noPlaceholders,
            scheme = scheme.copy(keyBackground = 999),
        )
        assertNotSame(first, second)
        assertEquals(2, KeyOverrideApplier.applyCacheSize())
    }

    @Test
    fun differentLayoutContent_misses() {
        val first = KeyOverrideApplier.apply(
            "X", layout("Q"), snapshot(subLabelOverrides()), noPlaceholders,
        )
        val second = KeyOverrideApplier.apply(
            "X", layout("W"), snapshot(subLabelOverrides()), noPlaceholders,
        )
        assertNotSame(first, second)
        assertEquals(2, KeyOverrideApplier.applyCacheSize())
    }

    @Test
    fun differentNotifications_misses() {
        val first = KeyOverrideApplier.apply(
            "X", layout(), snapshot(subLabelOverrides()), noPlaceholders,
        )
        val notification = SkinNotification(
            source = NotificationSource.PREEDIT,
            matchKey = null,
            matchValue = null,
            targetStyle = "s1",
            backgroundStyle = null,
            foregroundStyle = null,
            action = null,
            swipeUpAction = null,
            swipeDownAction = null,
        )
        val second = KeyOverrideApplier.apply(
            "X", layout(), snapshot(subLabelOverrides()), noPlaceholders,
            notifications = mapOf("s1" to notification),
        )
        assertNotSame(first, second)
        assertEquals(2, KeyOverrideApplier.applyCacheSize())
    }

    @Test
    fun capacity_boundedAt16() {
        repeat(17) { i ->
            KeyOverrideApplier.apply(
                "L$i", layout(), snapshot(subLabelOverrides("x$i")), noPlaceholders,
            )
        }
        assertEquals(16, KeyOverrideApplier.applyCacheSize())
    }

    @Test
    fun noTriggerApply_doesNotPopulateCache() {
        val factory = layout()
        val result = KeyOverrideApplier.apply("X", factory, snapshot(), noPlaceholders)
        assertSame(factory, result)
        assertEquals(0, KeyOverrideApplier.applyCacheSize())
    }
}
