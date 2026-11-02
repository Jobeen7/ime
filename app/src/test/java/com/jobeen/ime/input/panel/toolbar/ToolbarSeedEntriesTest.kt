package com.jobeen.ime.input.panel.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑起步列：皮肤未定义时取默认，皮肤已定义时仅池内项；
 * 写盘门控：只看不改不写盘，动手改才写盘。
 */
class ToolbarSeedEntriesTest {

    @Test
    fun seedDefaultsToFactoryFiveWithoutTraditionalChinese() {
        val seed = ToolbarUserStore.seedEntries(null)
        assertEquals(
            listOf("emoji", "select_all", "copy", "paste", "clipboard"),
            seed.map { it.ref },
        )
        assertTrue(seed.all { it.enabled })
    }

    @Test
    fun seedKeepsOnlyPoolItemsInOrder() {
        val seed = ToolbarUserStore.seedEntries(listOf("emoji", "unknown_id", "clipboard"))
        assertEquals(listOf("emoji", "clipboard"), seed.map { it.ref })
    }

    @Test
    fun seedExplicitEmptySkinStaysEmpty() {
        assertEquals(emptyList<ToolbarButtonEntry>(), ToolbarUserStore.seedEntries(emptyList()))
    }

    @Test
    fun shouldPersistWithoutSeedAlways() {
        assertTrue(
            ToolbarUserStore.shouldPersistEdit(null, listOf(ToolbarButtonEntry("emoji"))),
        )
    }

    @Test
    fun shouldNotPersistViewOnlyDraft() {
        val seed = ToolbarUserStore.seedEntries(null)
        assertFalse(ToolbarUserStore.shouldPersistEdit(seed, seed))
    }

    @Test
    fun shouldPersistOnAddRemoveReorderToggle() {
        val seed = ToolbarUserStore.seedEntries(null)
        assertTrue(
            ToolbarUserStore.shouldPersistEdit(seed, seed + ToolbarButtonEntry("symbol")),
        )
        assertTrue(
            ToolbarUserStore.shouldPersistEdit(seed, seed.drop(1)),
        )
        assertTrue(
            ToolbarUserStore.shouldPersistEdit(seed, seed.reversed()),
        )
        assertTrue(
            ToolbarUserStore.shouldPersistEdit(
                seed,
                seed.mapIndexed { i, e -> if (i == 0) e.copy(enabled = false) else e },
            ),
        )
    }
}
