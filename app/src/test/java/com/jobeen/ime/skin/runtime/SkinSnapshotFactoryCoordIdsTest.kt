package com.jobeen.ime.skin.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆写坐标布局并集（纯函数口径）：顶层绑定＋`keyboards` 表
 * 各类型绑定取并集；此前工厂只取顶层竖屏绑定，类型专属
 * 布局的坐标覆写在校验期即被当成未知键剔掉。
 */
class SkinSnapshotFactoryCoordIdsTest {

    private fun tree(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun emptyTree_noLayoutIds() {
        assertTrue(SkinSnapshotFactory.overrideLayoutIds(tree("{}")).isEmpty())
    }

    @Test
    fun primaryAndLandscapeBindings_included() {
        val ids = SkinSnapshotFactory.overrideLayoutIds(
            tree("""{"layoutId": "main", "layoutIdLandscape": "main-land"}"""),
        )
        assertEquals(setOf("main", "main-land"), ids)
    }

    @Test
    fun keyboardsTableBindings_unionWithPrimary() {
        val ids = SkinSnapshotFactory.overrideLayoutIds(
            tree(
                """{
                    "layoutId": "main",
                    "keyboards": {
                        "pinyin": {"layoutId": "t9", "layoutIdLandscape": "t9-land"},
                        "alphabetic": "qwerty",
                        "symbolic": {"layoutId": "main"}
                    }
                }""",
            ),
        )
        // main 重复出现只记一次；字符串简写即竖屏绑定。
        assertEquals(setOf("main", "t9", "t9-land", "qwerty"), ids)
    }

    @Test
    fun malformedEntries_skippedWithoutBreakingUnion() {
        val ids = SkinSnapshotFactory.overrideLayoutIds(
            tree(
                """{
                    "layoutId": "",
                    "layoutIdLandscape": 7,
                    "keyboards": {
                        "pinyin": {"layoutId": "  "},
                        "alphabetic": {"layoutId": "ok"},
                        "broken": 42,
                        "empty": {}
                    }
                }""",
            ),
        )
        assertEquals(setOf("ok"), ids)
    }

    @Test
    fun tableOnlySkin_primaryAbsentStillCovered() {
        // 无顶层绑定的纯表皮肤：类型专属布局坐标同样可覆写。
        val ids = SkinSnapshotFactory.overrideLayoutIds(
            tree("""{"keyboards": {"pinyin": "only"}}"""),
        )
        assertEquals(setOf("only"), ids)
    }
}
