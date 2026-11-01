package com.jobeen.ime.skin.layout

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.model.SkinAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** 横屏补丁展开成已解析布局（纯函数）：绑定 id、竖屏键身份与高度口径。 */
class LandscapePatchTest {

    private fun key(id: String, label: String) = LayoutKeyModel(
        stableId = id, label = label, widthWeight = 1.0, rowSpan = 1,
        alignSpanEnd = false, tap = SkinAction.Text(label), swipeUp = null,
        swipeDown = null, longPress = null,
    )

    private fun texts(layout: List<List<KeyDef>>): List<List<String>> =
        layout.map { row ->
            row.map { (it.appearance as KeyDef.Appearance.Text).displayText }
        }

    @Test
    fun expandToResolvedKeepsBindingIdAndPortraitKeyIdentity() {
        val portrait = LayoutModel(
            layoutId = "t9", name = "T9",
            rows = listOf(
                listOf(key("a", "a"), key("b", "b")),
                listOf(key("c", "c")),
            ),
            heightPercent = 55.0,
        )
        val patch = LayoutModel(
            layoutId = "t9-land", name = "patch",
            rows = listOf(
                listOf(key("b", "B-wide")),
                listOf(key("c", "c"), key("d", "d")),
            ),
            heightPercent = 48.0,
            patchOf = "t9",
        )
        val resolved = LandscapePatch.expandToResolved(
            patch, portrait, bindingId = "t9-land", generation = 7,
            placeholders = emptyMap(),
        )
        // 已解析布局的 id 取横屏绑定 id，name 与高度取展开结果（补丁高度优先）。
        assertEquals("t9-land", resolved.layoutId)
        assertEquals(7, resolved.generation)
        assertEquals("T9", resolved.name)
        assertEquals(48.0, resolved.heightPercent)
        assertEquals(
            listOf(listOf("a", "B-wide"), listOf("c", "d")),
            texts(resolved.baseLayout),
        )
        // 展开后键身份沿用竖屏布局：皮肤键覆写按竖屏键命中。
        assertEquals(
            listOf("t9:K-a", "t9:K-b"),
            resolved.baseLayout[0].map { it.id },
        )
        assertEquals(2, resolved.baseLayoutLandscape.size)
    }
}
