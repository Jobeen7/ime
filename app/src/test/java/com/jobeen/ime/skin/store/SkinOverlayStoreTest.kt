package com.jobeen.ime.skin.store

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖层存储工程（2026-10-29 item-1 D1）：三态合并＋读＋损坏降级。
 * 写路径已随 K-L1 删除（生产零调用方）：本文件只保留合并与读的测试。
 */
class SkinOverlayStoreTest {

    private fun base(): JsonObject = buildJsonObject {
        put("meta", buildJsonObject { put("id", "skin-1") })
        put("colors", buildJsonObject {
            put("keyFace", "#FF111111")
            put("keyText", "#FF222222")
        })
        put("fontFamily", "sans")
        put("toolbar", buildJsonObject {
            put("buttons", buildJsonObject { put("a", "b") })
        })
    }

    @Test
    fun mergeMissingKeyInheritsBase() {
        val (merged, issues) = SkinOverlayMerge.merge(
            base(),
            buildJsonObject { put("fontFamily", "serif") },
        )
        assertEquals("#FF111111", (merged["colors"] as JsonObject)["keyFace"].toString().trim('"'))
        assertEquals("serif", merged["fontFamily"].toString().trim('"'))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun mergeEmptyStringUnbindsKeyAndNestedKey() {
        val (merged, _) = SkinOverlayMerge.merge(
            base(),
            buildJsonObject {
                put("fontFamily", "")
                put("colors", buildJsonObject { put("keyFace", "") })
            },
        )
        // 顶层解绑：键消失（解析器随后取内置默认，非基础值）
        assertTrue("fontFamily" !in merged)
        // 嵌套解绑：仅该叶消失，其余继承
        val colors = merged["colors"] as JsonObject
        assertTrue("keyFace" !in colors)
        assertEquals("#FF222222", colors["keyText"].toString().trim('"'))
    }

    @Test
    fun mergeObjectRecursesAndArrayReplacesWholesale() {
        val (merged, _) = SkinOverlayMerge.merge(
            buildJsonObject {
                put("toolbar", buildJsonObject {
                    put("buttons", buildJsonObject { put("a", "1"); put("b", "2") })
                    put("other", "keep")
                })
            },
            buildJsonObject {
                put("toolbar", buildJsonObject {
                    put("buttons", buildJsonObject { put("a", "9") })
                })
            },
        )
        val toolbar = merged["toolbar"] as JsonObject
        val buttons = toolbar["buttons"] as JsonObject
        assertEquals("9", buttons["a"].toString().trim('"'))
        assertEquals("2", buttons["b"].toString().trim('"')) // 递归合并：未提及的键继承
        assertEquals("keep", toolbar["other"].toString().trim('"'))
    }

    @Test
    fun mergeReservedIdentityKeysSkipped() {
        val (merged, issues) = SkinOverlayMerge.merge(
            base(),
            buildJsonObject {
                put("meta", buildJsonObject { put("id", "skin-2") })
                put("id", "skin-2")
            },
        )
        assertEquals("skin-1", ((merged["meta"] as JsonObject)["id"]).toString().trim('"'))
        assertTrue(issues.any { it.contains("reserved_skipped") })
    }

    @Test
    fun mergeNullCountsAsUnbindWithIssue() {
        val (merged, issues) = SkinOverlayMerge.merge(
            base(),
            buildJsonObject { put("fontFamily", JsonNull) },
        )
        assertTrue("fontFamily" !in merged)
        assertTrue(issues.any { it.contains("null_unbind") })
    }

    @Test
    fun storeCorruptFileDegradesToNull() {
        val root = Files.createTempDirectory("skin-overlay-bad").toFile()
        val store = SkinOverlayStore(File(root, "skins"))
        try {
            val file = store.overlayFile("skin-1")
            file.parentFile.mkdirs()
            file.writeText("{not json")
            assertNull(store.read("skin-1"))
            file.writeText("""{"version": 0, "overrides": {}}""")
            assertNull(store.read("skin-1"))
            file.writeText("""{"version": 1}""")
            assertNull(store.read("skin-1"))
        } finally {
            root.deleteRecursively()
        }
    }

}
