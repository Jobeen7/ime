package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K-M4：内置最后一层布局——硬编码兜底布局的形态单测。
 * 布局本身是纯数据（不读 Context／偏好／快照），故可在 JVM 单测；
 * 工厂守卫的接线（KeyboardWindowView.createKeyboard 的 try/catch）
 * 走代码核对＋真机验收。
 */
class BuiltinFallbackLayoutTest {

    /** 单字母文本键的主显字母集合（空格键空串、图片键无主显，自动排除）。 */
    private fun letters(layout: List<List<KeyDef>>): Set<String> =
        layout.flatten().mapNotNull { def ->
            (def.appearance as? KeyDef.Appearance.Text)?.displayText
                ?.singleOrNull()?.toString()
        }.toSet()

    private fun hasPressAction(layout: List<List<KeyDef>>, action: KeyboardAction): Boolean =
        layout.flatten().any { def ->
            def.behaviors.any { behavior ->
                (behavior as? KeyDef.Behavior.Press)?.action == action
            }
        }

    @Test
    fun fourRowsWithFactoryShape() {
        val layout = BuiltinFallbackLayout.layout()
        assertEquals(4, layout.size)
        assertEquals(listOf(10, 9, 9, 5), layout.map { it.size })
        assertTrue(layout.all { row -> row.isNotEmpty() })
    }

    @Test
    fun coversAllLettersSpaceBackspaceReturn() {
        val layout = BuiltinFallbackLayout.layout()
        assertTrue(letters(layout).containsAll(('a'..'z').map { it.toString() }))
        assertTrue(hasPressAction(layout, KeyboardAction.SpaceAction))
        assertTrue(hasPressAction(layout, KeyboardAction.BackspaceAction))
        assertTrue(
            layout.flatten().any { it.appearance.viewId == KeyView.button_return },
        )
    }

    @Test
    fun rowWidthSumsMatchFactoryQwerty() {
        val sums = BuiltinFallbackLayout.layout().map { row ->
            row.sumOf { it.appearance.percentWidth.toDouble() }.toFloat()
        }
        // 行 1/3/4 和为 1.0；行 2 九键各 0.1，和为 0.9（出厂同款，
        // BaseKeyboard 以 spanScale 归一，逐像素行为与出厂一致）。
        val expected = listOf(1.0f, 0.9f, 1.0f, 1.0f)
        assertEquals(expected.size, sums.size)
        expected.zip(sums).forEach { (want, got) ->
            assertEquals(want, got, 0.001f)
        }
    }

    @Test
    fun keyIdsMatchFactoryQwerty() {
        // 兜底与出厂 Qwerty 键位／id 逐项相同（回车变体除外，见下个用例）；
        // 出厂布局改键时此处同步改，漂移即测试失败。
        val fallbackIds = BuiltinFallbackLayout.layout().flatten().map { it.id }
        val factoryIds = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Normal)
            .flatten().map { it.id }
        assertEquals(factoryIds, fallbackIds)
    }

    @Test
    fun returnKeyUsesNormalVariant() {
        val ret = BuiltinFallbackLayout.layout().flatten()
            .single { it.appearance.viewId == KeyView.button_return }
        assertEquals(KeyDef.Appearance.Variant.Normal, ret.appearance.variant)
    }

    @Test
    fun deterministicAcrossCalls() {
        // 两次调用键 id 与主显文本逐项相同：无共享可变状态、无随机性。
        val first = BuiltinFallbackLayout.layout()
        val second = BuiltinFallbackLayout.layout()
        assertEquals(
            first.flatten().map { it.id },
            second.flatten().map { it.id },
        )
        assertEquals(
            first.flatten().map { (it.appearance as? KeyDef.Appearance.Text)?.displayText },
            second.flatten().map { (it.appearance as? KeyDef.Appearance.Text)?.displayText },
        )
    }
}
