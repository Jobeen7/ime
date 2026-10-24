// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemeArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四期归档 / 恢复逃生舱：归档规划（排除项与 userdb 分流）、
 * 清单编解码、归档目录拆分、恢复规划（覆盖 / 清理 / userdb
 * 保护 / 重建顺序）。界面与文件 IO 不在此测（需真机复核）。
 */
class SchemeArchiveTest {

    @Test
    fun planArchive_splitsFilesAndUserdb_excludesManaged() {
        val userFiles = setOf(
            "wanxiang_pro.schema.yaml",
            "wanxiang_pro.dict.yaml",
            "wanxiang_pro.custom.yaml",
            "opencc/wanxiang_pinyin.json",
            "zc.userdb",
            "wanxiang.userdb",
            "user.yaml",
            "installation.yaml",
            "build/wanxiang_pro.yaml",
            "scheme-archive/old-123/wanxiang_pro.schema.yaml",
        )
        val plan = SchemeArchive.planArchive(
            "测试归档",
            1720000000000L,
            userFiles,
            listOf("wanxiang_pro"),
        )!!
        assertEquals("测试归档", plan.name)
        assertEquals(
            listOf(
                "opencc/wanxiang_pinyin.json",
                "wanxiang_pro.custom.yaml",
                "wanxiang_pro.dict.yaml",
                "wanxiang_pro.schema.yaml",
            ),
            plan.files,
        )
        assertEquals(listOf("wanxiang.userdb", "zc.userdb"), plan.userdbFiles)
        assertTrue(plan.dirName.startsWith("测试归档-1720000000000"))
    }

    @Test
    fun planArchive_refusesEmpty() {
        assertNull(
            SchemeArchive.planArchive("x", 1L, setOf("a.schema.yaml"), emptyList()),
        )
        assertNull(
            SchemeArchive.planArchive("  ", 1L, setOf("a.schema.yaml"), listOf("a")),
        )
        assertNull(
            SchemeArchive.planArchive(
                "x",
                1L,
                setOf("user.yaml", "build/a.yaml"),
                listOf("a"),
            ),
        )
    }

    @Test
    fun manifest_roundTrip_andDirtyLines() {
        val manifest = SchemeArchive.ArchiveManifest(
            "我的归档",
            1720000000000L,
            listOf("wanxiang_pro", "wanxiang_pure"),
        )
        val decoded = SchemeArchive.decodeManifest(SchemeArchive.encodeManifest(manifest))
        assertEquals(manifest, decoded)
        assertNull(SchemeArchive.decodeManifest(null))
        assertNull(SchemeArchive.decodeManifest("v2\nname\tx\ncreated\t1\nschemes\ta"))
        assertNull(
            SchemeArchive.decodeManifest("v1\nname\tx\ncreated\tnope\nschemes\ta"),
        )
        assertNull(SchemeArchive.decodeManifest("v1\nname\tx\ncreated\t1\nschemes\t"))
        val withUnknown = SchemeArchive.decodeManifest(
            "v1\nname\tx\ncreated\t5\nunknown\tzzz\nschemes\ta,b",
        )
        assertEquals(listOf("a", "b"), withUnknown?.schemeIds)
    }

    @Test
    fun splitArchiveListing_excludesManifestAndRegistry() {
        val (files, userdb) = SchemeArchive.splitArchiveListing(
            setOf(
                "a.schema.yaml",
                "zc.userdb",
                SchemeArchive.MANIFEST_FILE_NAME,
                SchemeArchive.REGISTRY_FILE_NAME,
            ),
        )
        assertEquals(listOf("a.schema.yaml"), files)
        assertEquals(listOf("zc.userdb"), userdb)
    }

    @Test
    fun planRestore_countsAndUserdbProtection() {
        val plan = SchemeArchive.planRestore(
            archiveFiles = listOf("a.schema.yaml", "a.dict.yaml", "b.schema.yaml"),
            archiveUserdb = listOf("a.userdb", "b.userdb"),
            currentUserFiles = setOf("a.schema.yaml", "a.userdb", "old.dict.yaml", "stray.yaml"),
            currentRegistryFiles = setOf("a.schema.yaml", "old.dict.yaml"),
            schemeIds = listOf("a", "b"),
            overwriteUserdb = false,
        )
        assertEquals(
            listOf("a.dict.yaml", "a.schema.yaml", "b.schema.yaml"),
            plan.restoreFiles,
        )
        assertEquals(listOf("a.schema.yaml"), plan.replaceExisting)
        // 清理只认登记文件：登记外的 stray.yaml 不动。
        assertEquals(listOf("old.dict.yaml"), plan.cleanFiles)
        // 默认：缺失 userdb 恢复、已存在 userdb 跳过不覆盖。
        assertEquals(listOf("b.userdb"), plan.userdbRestoreMissing)
        assertEquals(listOf("a.userdb"), plan.userdbSkippedExisting)
        assertTrue(plan.userdbOverwrite.isEmpty())
        assertEquals(listOf("a", "b"), plan.newOrder)
    }

    @Test
    fun planRestore_overwriteUserdbChecked() {
        val plan = SchemeArchive.planRestore(
            archiveFiles = listOf("a.schema.yaml"),
            archiveUserdb = listOf("a.userdb"),
            currentUserFiles = setOf("a.userdb"),
            currentRegistryFiles = emptySet(),
            schemeIds = listOf("a"),
            overwriteUserdb = true,
        )
        assertTrue(plan.userdbSkippedExisting.isEmpty())
        assertEquals(listOf("a.userdb"), plan.userdbOverwrite)
        assertTrue(plan.userdbRestoreMissing.isEmpty())
    }

    @Test
    fun planRestore_neverCleansUserdbOrManaged() {
        val plan = SchemeArchive.planRestore(
            archiveFiles = listOf("a.schema.yaml"),
            archiveUserdb = emptyList(),
            currentUserFiles = setOf("x.userdb", "user.yaml"),
            currentRegistryFiles = setOf("x.userdb", "user.yaml", "build/a.yaml"),
            schemeIds = listOf("a"),
            overwriteUserdb = false,
        )
        assertTrue(plan.cleanFiles.isEmpty())
    }

    @Test
    fun rebuildOrder_dedupsPreservingOrder() {
        assertEquals(
            listOf("a", "b"),
            SchemeArchive.rebuildOrder(listOf("a", "b", "a", "")),
        )
    }
}
