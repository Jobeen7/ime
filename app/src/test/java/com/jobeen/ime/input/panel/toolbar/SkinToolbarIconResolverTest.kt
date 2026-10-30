package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.skin.model.SkinToolbarButtonSnapshot
import com.jobeen.ime.skin.model.SkinToolbarIconRef
import com.jobeen.ime.skin.model.SkinToolbarSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 图标引用收集（2026-10-27）：中央区 + 左右边缘去重，解码本身走真机验证。 */
class SkinToolbarIconResolverTest {

    @Test
    fun collectIconRefsDedupesAcrossCenterAndEdges() {
        val refs = SkinToolbarIconResolver.collectIconRefs(
            SkinToolbarSnapshot(
                present = true,
                buttons = listOf(
                    SkinToolbarButtonSnapshot("emoji", SkinToolbarIconRef("tb-shared", "e".repeat(64), "png")),
                    SkinToolbarButtonSnapshot("copy", null),
                    SkinToolbarButtonSnapshot("voice", SkinToolbarIconRef("tb-voice", "v".repeat(64), "webp")),
                ),
                expandIcon = SkinToolbarIconRef("tb-shared", "e".repeat(64), "png"),
                collapseIcon = SkinToolbarIconRef("tb-collapse", "c".repeat(64), "png"),
            ),
        )
        assertEquals(listOf("tb-shared", "tb-voice", "tb-collapse"), refs.map { it.assetId })
    }

    @Test
    fun collectIconRefsEmptyWhenNoIcons() {
        val refs = SkinToolbarIconResolver.collectIconRefs(
            SkinToolbarSnapshot(
                present = true,
                buttons = listOf(SkinToolbarButtonSnapshot("copy", null)),
            ),
        )
        assertEquals(emptyList<SkinToolbarIconRef>(), refs)
    }
}
