package com.jobeen.ime.base.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-L4（版本比较）／B-L5（内置版本兜底）／B-M6（临时包清理）／
 * B-M3（中断恢复按根修复）。
 */
class WanxiangUpdateManagerTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("wum-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- B-L4 ----

    @Test
    fun compareVersions_prereleaseIsLessThanRelease() {
        assertTrue(WanxiangUpdateManager.compareVersions("v18.1.12-rc1", "v18.1.12") < 0)
        assertTrue(WanxiangUpdateManager.compareVersions("v18.1.12", "v18.1.12-rc1") > 0)
        assertTrue(WanxiangUpdateManager.compareVersions("v18.1.12-rc1", "v18.1.12-rc2") < 0)
        assertTrue(WanxiangUpdateManager.compareVersions("1.1.18-beta", "1.1.18") < 0)
    }

    @Test
    fun compareVersions_plainNumericUnchanged() {
        assertEquals(0, WanxiangUpdateManager.compareVersions("v18.1.7", "18.1.7"))
        assertTrue(WanxiangUpdateManager.compareVersions("18.1.7", "18.1.6") > 0)
        assertTrue(WanxiangUpdateManager.compareVersions("1.0", "1.0.1") < 0)
        assertTrue(WanxiangUpdateManager.compareVersions("LTS", "18.1.7") < 0)
    }

    // ---- B-L5 ----

    @Test
    fun builtinVersion_matchesBundledResource() {
        // resource.zip 内 shared/version.txt 为 18.1.11
        assertEquals("v18.1.11", WanxiangUpdateManager.BUILTIN_SCHEMA_VERSION)
    }

    // ---- B-M6 ----

    @Test
    fun deleteTempPackageFiles_removesOnlyNamedVariant() {
        runWithTempDir { workDir ->
            for (name in listOf("rime-wanxiang-lite.zip", "rime-wanxiang-base.zip", "rime-wanxiang-pure.zip")) {
                File(workDir, name).writeText("zip")
                File(workDir, "$name.part").writeText("part")
            }
            File(workDir, "keep.txt").writeText("keep")
            WanxiangUpdateManager.deleteTempPackageFiles(workDir, "rime-wanxiang-base.zip")
            assertFalse(File(workDir, "rime-wanxiang-base.zip").exists())
            assertFalse(File(workDir, "rime-wanxiang-base.zip.part").exists())
            assertTrue(File(workDir, "rime-wanxiang-lite.zip").exists())
            assertTrue(File(workDir, "rime-wanxiang-lite.zip.part").exists())
            assertTrue(File(workDir, "rime-wanxiang-pure.zip").exists())
            assertTrue(File(workDir, "rime-wanxiang-pure.zip.part").exists())
            assertTrue(File(workDir, "keep.txt").exists())
        }
    }

    // ---- B-M3（按根修复；目录枚举本身在 recoverInterruptedUpdateInternal 内） ----

    @Test
    fun recoverDictsRoot_missingDictsRestoresTrash() {
        runWithTempDir { root ->
            val trash = File(root, "dicts.trash").apply { mkdirs() }
            File(trash, "wanxiang.dict.yaml").writeText("dict")
            WanxiangUpdateManager.recoverDictsRoot(root)
            assertTrue(File(root, "dicts/wanxiang.dict.yaml").isFile)
            assertFalse(File(root, "dicts.trash").exists())
        }
    }

    @Test
    fun recoverDictsRoot_dictsPresentDeletesTrash() {
        runWithTempDir { root ->
            File(root, "dicts").apply { mkdirs() }
            File(root, "dicts/current.dict.yaml").writeText("current")
            File(root, "dicts.trash").apply { mkdirs() }
            File(root, "dicts.trash/old.dict.yaml").writeText("old")
            WanxiangUpdateManager.recoverDictsRoot(root)
            assertTrue(File(root, "dicts/current.dict.yaml").isFile)
            assertFalse(File(root, "dicts.trash").exists())
        }
    }

    @Test
    fun recoverDictsRoot_clearsStagingAndIsNoopWhenClean() {
        runWithTempDir { root ->
            File(root, "dicts").apply { mkdirs() }
            File(root, "dicts.staging").apply { mkdirs() }
            File(root, "dicts.staging/x.dict.yaml").writeText("x")
            WanxiangUpdateManager.recoverDictsRoot(root)
            assertFalse(File(root, "dicts.staging").exists())
            assertTrue(File(root, "dicts").isDirectory)
            // 再跑一次：干净状态无操作、不抛错
            WanxiangUpdateManager.recoverDictsRoot(root)
            assertTrue(File(root, "dicts").isDirectory)
        }
    }
}
