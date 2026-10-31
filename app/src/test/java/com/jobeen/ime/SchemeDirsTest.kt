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
    fun importDirName_protectsInitialAndBuild() {
        assertEquals("rime-wanxiang-base", SchemeDirs.importDirName("rime-wanxiang-base"))
        assertEquals("default-pkg", SchemeDirs.importDirName("default"))
        assertEquals("build-pkg", SchemeDirs.importDirName("build"))
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
    fun registryDirField_roundTripsAndOldRowsDefaultEmpty() {        val registry = SchemePackageRegistry(
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

    /** 内存迁移状态：模拟 SharedPreferences 两组键，写失败/忽略写可配。 */
    private class FakeMigrationStore(
        var legacy: String?,
        var dir: String?,
        var throwOnDirWrite: Boolean = false,
        var ignoreDirWrites: Boolean = false,
    ) : SchemeDirs.MigrationStore {
        var migrated = false
        var error: String? = null

        override fun isMigrated() = migrated
        override fun setMigrated() {
            migrated = true
            error = null
        }

        override fun recordError(message: String) {
            error = message
        }

        override fun legacyEnabledIds() = legacy
        override fun dirEnabledIds() = dir
        override fun setDirEnabledIds(value: String) {
            if (throwOnDirWrite) throw RuntimeException("write failed")
            if (!ignoreDirWrites) dir = value
        }
    }

    @Test
    fun migrationPlan_splitsMovesAndConflictsAndExcludesBackup() {
        val root = Files.createTempDirectory("schemedirs-plan").toFile()
        try {
            File(root, "plain.schema.yaml").writeText("plain")
            File(root, "clash.schema.yaml").writeText("new")
            File(root, "default").mkdirs()
            File(root, "default/clash.schema.yaml").writeText("old")
            File(root, "build").mkdirs()
            File(root, ".migration-backup").mkdirs()
            val plan = SchemeDirs.migrationPlan(root)
            assertEquals(listOf("plain.schema.yaml"), plan.moves.map { it.first.name })
            assertEquals(listOf("clash.schema.yaml"), plan.conflicts.map { it.first.name })
            // 兼容旧签名：移动表不变，冲突项不在其中。
            assertEquals(plan.moves, SchemeDirs.migrationMoves(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun listDirs_excludesBuildHiddenAndFiles() {
        val root = Files.createTempDirectory("schemedirs-list").toFile()
        try {
            File(root, "b-dir").mkdirs()
            File(root, "a-dir").mkdirs()
            File(root, "build").mkdirs()
            File(root, ".hidden").mkdirs()
            File(root, "loose.txt").writeText("x")
            assertEquals(
                listOf("a-dir", "b-dir"),
                SchemeDirs.listDirs(root).map { it.name },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_renameFailure_doesNotSetAndRetries() {
        val root = Files.createTempDirectory("schemedirs-retry").toFile()
        try {
            // 目标父级是普通文件：mkdirs 无效，renameTo 必然返回 false。
            File(root, "sub").writeText("blocker")
            File(root, "a.schema.yaml").writeText("a")
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store) {
                SchemeDirs.MigrationPlan(
                    listOf(
                        File(root, "a.schema.yaml") to
                            File(File(root, "sub"), "a.schema.yaml"),
                    ),
                    emptyList(),
                )
            }
            assertFalse(store.migrated)
            assertTrue(store.error?.contains("move failed") == true)
            assertTrue(File(root, "a.schema.yaml").exists())
            // 排除占位后重试：移动成功并置位，错误清除。
            File(root, "sub").delete()
            SchemeDirs.ensureMigrated(root, store)
            assertTrue(store.migrated)
            assertEquals(null, store.error)
            assertFalse(File(root, "a.schema.yaml").exists())
            assertEquals("a", File(root, "default/a.schema.yaml").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_initialDirBlockedByFile_doesNotSet() {
        val root = Files.createTempDirectory("schemedirs-blocked").toFile()
        try {
            // 初始目录位置被普通文件占住：建不出初始目录，迁移不置位。
            File(root, "default").writeText("blocker")
            File(root, "a.schema.yaml").writeText("a")
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store)
            assertFalse(store.migrated)
            assertTrue(store.error?.contains("blocked") == true)
            assertTrue(File(root, "a.schema.yaml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_sameContentConflict_cleansSourceAndSets() {
        val root = Files.createTempDirectory("schemedirs-same").toFile()
        try {
            File(root, "default").mkdirs()
            File(root, "default/x.schema.yaml").writeText("identical")
            File(root, "x.schema.yaml").writeText("identical")
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store)
            assertTrue(store.migrated)
            assertFalse(File(root, "x.schema.yaml").exists())
            assertEquals("identical", File(root, "default/x.schema.yaml").readText())
            assertFalse(File(root, ".migration-backup").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_sameContentDirConflict_cleansSourceDirAndSets() {
        val root = Files.createTempDirectory("schemedirs-samedir").toFile()
        try {
            File(root, "default").mkdirs()
            File(root, "default/lua").mkdirs()
            File(root, "default/lua/x.lua").writeText("-- lua")
            File(root, "lua").mkdirs()
            File(root, "lua/x.lua").writeText("-- lua")
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store)
            assertTrue(store.migrated)
            assertFalse(File(root, "lua").exists())
            assertEquals("-- lua", File(root, "default/lua/x.lua").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_differentContentConflict_keepsBothWithBackupAndSets() {
        val root = Files.createTempDirectory("schemedirs-diff").toFile()
        try {
            File(root, "default").mkdirs()
            File(root, "default/x.schema.yaml").writeText("old")
            File(root, "x.schema.yaml").writeText("new")
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store)
            assertTrue(store.migrated)
            assertEquals("old", File(root, "default/x.schema.yaml").readText())
            assertEquals("new", File(root, ".migration-backup/x.schema.yaml").readText())
            assertFalse(File(root, "x.schema.yaml").exists())
            // 幂等：已置位后重复调用不再动作，备份不重复。
            SchemeDirs.ensureMigrated(root, store)
            assertEquals(
                listOf("x.schema.yaml"),
                File(root, ".migration-backup").listFiles().orEmpty().map { it.name },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_prefCopyWriteFailure_doesNotSet() {
        val root = Files.createTempDirectory("schemedirs-prefwrite").toFile()
        try {
            File(root, "a.schema.yaml").writeText("a")
            val store = FakeMigrationStore(
                legacy = "wanxiang_lite",
                dir = null,
                throwOnDirWrite = true,
            )
            SchemeDirs.ensureMigrated(root, store)
            assertFalse(store.migrated)
            assertTrue(store.error?.contains("enabled ids") == true)
            assertEquals(null, store.dir)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_prefCopyReadbackMismatch_doesNotSet() {
        val root = Files.createTempDirectory("schemedirs-prefread").toFile()
        try {
            val store = FakeMigrationStore(
                legacy = "wanxiang_lite",
                dir = null,
                ignoreDirWrites = true,
            )
            SchemeDirs.ensureMigrated(root, store)
            assertFalse(store.migrated)
            assertTrue(store.error?.contains("verify") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_planException_doesNotSet() {
        val root = Files.createTempDirectory("schemedirs-planfail").toFile()
        try {
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store) { throw RuntimeException("boom") }
            assertFalse(store.migrated)
            assertTrue(store.error?.contains("boom") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_leftoverFile_doesNotSet() {
        val root = Files.createTempDirectory("schemedirs-leftover").toFile()
        try {
            File(root, "missed.schema.yaml").writeText("missed")
            val store = FakeMigrationStore(legacy = null, dir = null)
            // 规划漏掉根下已存在的文件（模拟规划后新文件出现）：终检查拦截。
            SchemeDirs.ensureMigrated(root, store) {
                SchemeDirs.MigrationPlan(emptyList(), emptyList())
            }
            assertFalse(store.migrated)
            assertTrue(store.error?.contains("leftover") == true)
            assertTrue(File(root, "missed.schema.yaml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_alreadyMigrated_returnsImmediately() {
        val root = Files.createTempDirectory("schemedirs-done").toFile()
        try {
            File(root, "a.schema.yaml").writeText("a")
            val store = FakeMigrationStore(legacy = null, dir = null)
            store.migrated = true
            SchemeDirs.ensureMigrated(root, store)
            assertTrue(File(root, "a.schema.yaml").exists())
            assertEquals(null, store.error)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- 迁移枚举 null 与空区分 ----

    /** listFiles() 恒返回 null 的 File：模拟目录枚举失败（权限/IO 故障）。 */
    private class NullListDir(path: String) : File(path) {
        override fun listFiles(): Array<File>? = null
    }

    @Test
    fun migrationPlan_nullListingThrowsInsteadOfEmptyPlan() {
        val real = Files.createTempDirectory("schemedirs-null").toFile()
        try {
            File(real, "a.schema.yaml").writeText("a")
            val root = NullListDir(real.absolutePath)
            var thrown: String? = null
            try {
                SchemeDirs.migrationPlan(root)
            } catch (e: IllegalStateException) {
                thrown = e.message
            }
            // 枚举失败不按空表处理：空表会被当成“无待迁移文件”而置位，
            // 旧方案永久丢失；抛错后 ensureMigrated 记错误、不置位、可重试。
            assertTrue(thrown != null && thrown.contains("cannot list"))
        } finally {
            real.deleteRecursively()
        }
    }

    @Test
    fun ensureMigrated_nullListing_doesNotSetAndRecordsError() {
        val real = Files.createTempDirectory("schemedirs-null").toFile()
        try {
            File(real, "a.schema.yaml").writeText("a")
            val root = NullListDir(real.absolutePath)
            val store = FakeMigrationStore(legacy = null, dir = null)
            SchemeDirs.ensureMigrated(root, store)
            assertFalse(store.migrated)
            assertTrue(store.error != null && store.error!!.contains("cannot list"))
        } finally {
            real.deleteRecursively()
        }
    }

    @Test
    fun runMigration_nullListing_returnsFailureInsteadOfClean() {
        val real = Files.createTempDirectory("schemedirs-null").toFile()
        try {
            File(real, "a.schema.yaml").writeText("a")
            val root = NullListDir(real.absolutePath)
            val store = FakeMigrationStore(legacy = null, dir = null)
            val failure = SchemeDirs.runMigration(
                root,
                SchemeDirs.MigrationPlan(emptyList(), emptyList()),
                store,
            )
            // 终检枚举失败与“无剩余文件”是两回事：前者必须返回失败、
            // 不置位，否则会把未处理文件误当成已处理。
            assertTrue(failure != null && failure.contains("cannot list"))
        } finally {
            real.deleteRecursively()
        }
    }
}
