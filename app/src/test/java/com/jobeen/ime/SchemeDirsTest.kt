// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemeDirs
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.SchemePackageImport.SchemePackageRecord
import com.jobeen.ime.data.manager.SchemePackageImport.SchemePackageRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多目录模型纯逻辑：目录名净化、迁移移动表、目录方案表读写、登记目录字段。 */
class SchemeDirsTest {

    @Test
    fun sanitizeDirName_keepsSafeCharsAndFallsBack() {
        assertEquals("rime-wanxiang-base", SchemeDirs.sanitizeDirName("rime-wanxiang-base"))
        assertEquals("a_b", SchemeDirs.sanitizeDirName("a/b"))
        assertEquals("default", SchemeDirs.sanitizeDirName("///"))
        assertTrue(SchemeDirs.isValidDirName("rime-ice"))
        assertFalse(SchemeDirs.isValidDirName("../escape"))
        assertFalse(SchemeDirs.isValidDirName(""))
    }

    @Test
    fun migrationMoves_movesFlatChildrenExceptBuild() {
        val root = Files.createTempDirectory("schemedirs-root").toFile()
        try {
            File(root, "wanxiang_lite.schema.yaml").writeText("schema:\n  name: Lite\n")
            File(root, "lua").mkdirs()
            File(root, "lua/x.lua").writeText("-- lua")
            File(root, "build").mkdirs()
            File(root, "build/x.yaml").writeText("built")
            val moves = SchemeDirs.migrationMoves(root)
            val destNames = moves.map { it.second.parentFile?.name to it.second.name }.toSet()
            assertTrue(destNames.contains("default" to "wanxiang_lite.schema.yaml"))
            assertTrue(destNames.contains("default" to "lua"))
            assertFalse(moves.any { it.first.name == "build" })
            // 目标已存在时跳过，不覆盖。
            File(root, "default").mkdirs()
            File(root, "default/wanxiang_lite.schema.yaml").writeText("existing")
            val moves2 = SchemeDirs.migrationMoves(root)
            assertFalse(moves2.any { it.first.name == "wanxiang_lite.schema.yaml" })
            assertTrue(moves2.any { it.first.name == "lua" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultCustom_roundTripsThroughParser() {
        val dir = Files.createTempDirectory("schemedirs-dir").toFile()
        try {
            SchemeDirs.writeDefaultCustomSchemaList(dir, listOf("wanxiang_pro", "wanxiang_t9_pro"))
            assertEquals(
                listOf("wanxiang_pro", "wanxiang_t9_pro"),
                SchemeDirs.schemesInDir(dir),
            )
            // 空表删文件，方案表回落文件名。
            SchemeDirs.writeDefaultCustomSchemaList(dir, emptyList())
            assertFalse(File(dir, "default.custom.yaml").exists())
            File(dir, "b.schema.yaml").writeText("schema:\n  name: B\n")
            File(dir, "a.schema.yaml").writeText("schema:\n  name: A\n")
            assertEquals(listOf("a", "b"), SchemeDirs.schemesInDir(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun registryDirField_roundTripsAndOldRowsDefaultEmpty() {
        val registry = SchemePackageRegistry(
            listOf(
                SchemePackageRecord(
                    packageName = "rime-wanxiang-base",
                    version = "18.1.7",
                    schemeIds = listOf("wanxiang_base"),
                    files = listOf("wanxiang_base.schema.yaml"),
                    source = SchemePackageImport.PackageSource.LOCAL,
                    dir = "rime-wanxiang-base",
                ),
            ),
        )
        val decoded = SchemePackageImport.decodeRegistry(
            SchemePackageImport.encodeRegistry(registry),
        )
        assertEquals(registry, decoded)
        assertEquals("rime-wanxiang-base", decoded.records[0].dir)
        // 五字段旧行解码后目录为空。
        val old = SchemePackageImport.decodeRegistry(
            "pkg-a\t18.1.7\tx\tx.schema.yaml\tlocal",
        )
        assertEquals("", old.records[0].dir)
    }
}
