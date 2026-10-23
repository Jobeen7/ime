package com.jobeen.ime.skin.draft

import com.jobeen.ime.skin.model.SkinJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 皮肤树的 layoutId 改绑/解绑（B3 详设 §4.6）。 */
class SkinTreeEditorLayoutBindingTest {

    private fun tree(json: String): JsonObject = SkinJson.parseObject(json)!!

    @Test
    fun setLayoutIdBindsRebindsAndUnbinds() {
        val base = tree(
            """{"formatVersion":3,"meta":{"id":"s1","name":"S"},"layoutId":"lay-a"}""",
        )
        assertEquals("lay-a", SkinTreeEditor.layoutIdOf(base))
        val rebound = SkinTreeEditor.setLayoutId(base, "lay-b")
        assertEquals("lay-b", SkinTreeEditor.layoutIdOf(rebound))
        // 其余节点不动
        assertEquals("s1", SkinTreeEditor.skinIdOf(rebound))
        val unbound = SkinTreeEditor.setLayoutId(rebound, null)
        assertNull(SkinTreeEditor.layoutIdOf(unbound))
        assertFalse("layoutId" in unbound)
        // 未绑定树上新绑
        val bound = SkinTreeEditor.setLayoutId(
            tree("""{"formatVersion":3,"meta":{"id":"s2","name":"T"}}"""), "lay-a",
        )
        assertEquals("lay-a", SkinTreeEditor.layoutIdOf(bound))
    }
}
