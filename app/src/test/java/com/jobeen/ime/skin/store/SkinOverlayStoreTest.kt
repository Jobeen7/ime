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

/** 覆盖层存储工程（2026-10-29 item-1 D1）：三态合并＋存储＋防抖＋版本。 */
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
    fun storeVersionIncrementsAndRoundTrips() {
        val root = Files.createTempDirectory("skin-overlay").toFile()
        val store = SkinOverlayStore(File(root, "skins"))
        try {
            assertNull(store.read("skin-1"))
            val v1 = store.saveNow("skin-1", buildJsonObject { put("fontFamily", "serif") })
            assertEquals(1L, v1)
            val read1 = store.read("skin-1")!!
            assertEquals(1L, read1.version)
            assertEquals(
                "serif",
                read1.overrides["fontFamily"].toString().trim('"'),
            )
            val v2 = store.saveNow("skin-1", buildJsonObject { put("fontFamily", "mono") })
            assertEquals(2L, v2)
            // 同 id 覆盖层与代次无关：UPDATE（新代次）后仍读得到
            assertEquals(2L, store.read("skin-1")!!.version)
            // COPY（换新 id）下无覆盖层：从干净基础开始
            assertNull(store.read("skin-1-copy"))
            store.clear("skin-1")
            assertNull(store.read("skin-1"))
        } finally {
            store.shutdown()
            root.deleteRecursively()
        }
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
            store.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun debouncedSavesCoalesceToOneWrite() {
        val root = Files.createTempDirectory("skin-overlay-debounce").toFile()
        val store = SkinOverlayStore(File(root, "skins"))
        try {
            store.saveDebounced("skin-1", buildJsonObject { put("fontFamily", "a") })
            store.saveDebounced("skin-1", buildJsonObject { put("fontFamily", "b") })
            store.saveDebounced("skin-1", buildJsonObject { put("fontFamily", "c") })
            store.flush()
            val read = store.read("skin-1")!!
            assertEquals(1L, read.version) // 三次调用只落盘一次
            assertEquals("c", read.overrides["fontFamily"].toString().trim('"'))
            store.saveDebounced("skin-1", buildJsonObject { put("fontFamily", "d") })
            store.flush()
            assertEquals(2L, store.read("skin-1")!!.version)
        } finally {
            store.shutdown()
            root.deleteRecursively()
        }
    }
}
