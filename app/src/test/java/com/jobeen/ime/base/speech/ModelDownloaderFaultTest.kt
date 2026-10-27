package com.jobeen.ime.base.speech

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * R1-3：模型换装备份恢复未验结果。
 *
 * 旧代码（installModel）：正目录缺失且 .old 存在时 backup.renameTo(modelDir)
 * 返回值不检查，随即 backup.deleteRecursively() 无条件删备份——恢复失败则
 * 最后一把旧模型丢失；staging 就位失败时的回滚 rename 同样不检查。
 * 新规则（replaceModelDirectory）：只有确认备份恢复成功、或新模型完整提交
 * 并在位校验后，才删 .old；每个 rename/delete 都验返回值并抛带步骤与
 * 备份去向的 IOException（调用方 extractAndInstall 捕获后返回 false，
 * 下载层报失败）；回滚失败时备份原地保留，下次安装仍可自愈。
 *
 * 注入法（主机可跑、无需 Android Context）：replaceModelDirectory 的
 * rename 参数生产默认即 File.renameTo，测试以此注入失败。失败分支不用
 * 文件系统状态注入，原因已实测：本机测试用户是 root，只读父目录
 * （0555）下 rename 仍成功；目录→非空目录/文件虽必败，但自愈分支的
 * 目标路径按构造不可能处于那种状态（自愈要求正目录不存在、就位要求
 * 旧目录已让位）。成功路径、陈旧备份、残缺在位三条全部走真实 renameTo；
 * 跨设备用例再以真实 renameTo 失败覆盖一次就位失败回滚分支。
 *
 * 语-O2（下载器内互斥）不在此文件覆盖：download() 需 Android Context、
 * 联网与 App 目录，本模块单测无 Robolectric，主机上跑不起来；互斥正确性
 * 由 kotlinx Mutex 契约＋单次获取审计保证（见 ModelDownloader.download
 * 注释），编译与行为由协调员全量测试验证。
 */
class ModelDownloaderFaultTest {

    private val modelFileNames = listOf(
        "tokens.txt",
        "encoder.onnx",
        "decoder.onnx",
        "joiner.onnx",
    )

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("model-downloader-fault-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 在 dir 里写一套完整模型，4 个文件内容均为 tag（区分 old/new/older）。 */
    private fun writeModel(dir: File, tag: String) {
        dir.mkdirs()
        assertTrue("model dir not created: $dir", dir.isDirectory)
        for (name in modelFileNames) {
            File(dir, name).writeText(tag)
        }
    }

    private fun assertModelContents(dir: File, tag: String) {
        assertTrue("model dir missing: $dir", dir.isDirectory)
        for (name in modelFileNames) {
            assertEquals(tag, File(dir, name).readText())
        }
    }

    private fun modelDir(root: File) = File(root, "speech")

    private fun backupOf(root: File) = File(root, "speech.old")

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        fail("expected IOException")
        throw AssertionError("unreachable")
    }

    /** 按调用序号注入失败：序号在 failCalls 内的 rename 返回 false，其余走真实 renameTo。 */
    private fun failCallsRename(failCalls: Set<Int>): (File, File) -> Boolean {
        var call = 0
        return { source, destination ->
            call += 1
            if (call in failCalls) false else source.renameTo(destination)
        }
    }

    @Test
    fun success_replacesOldModel_deletesBackup() {
        runWithTempDir { root ->
            val model = modelDir(root)
            val staging = File(root, "staging")
            writeModel(model, "old")
            writeModel(staging, "new")
            ModelDownloader.replaceModelDirectory(staging, model)
            assertModelContents(model, "new")
            assertFalse(backupOf(root).exists())
            assertFalse("staging must be renamed away, not left behind", staging.exists())
        }
    }

    @Test
    fun restoreFailure_keepsBackup_reportsExplicitError() {
        runWithTempDir { root ->
            val model = modelDir(root)
            val backup = backupOf(root)
            val staging = File(root, "staging")
            writeModel(backup, "old")
            writeModel(staging, "new")
            // 自愈恢复（第 1 次 rename）失败：旧代码随后无条件删备份，
            // 新代码必须抛错且保留备份。
            val error = expectIOException {
                ModelDownloader.replaceModelDirectory(staging, model) { _, _ -> false }
            }
            val message = error.message ?: ""
            assertTrue(message.contains("Failed to restore model backup"))
            assertTrue(message.contains("backup kept"))
            assertModelContents(backup, "old")
            assertFalse("failed restore must not create a model dir", model.exists())
        }
    }

    @Test
    fun stagingFailure_withRollbackSuccess_restoresOldModel() {
        runWithTempDir { root ->
            val model = modelDir(root)
            val staging = File(root, "staging")
            writeModel(model, "old")
            writeModel(staging, "new")
            // 调用序：1 旧目录让位（真实成功）、2 新目录就位（注入失败）、
            // 3 回滚（真实成功）。旧模型必须原样回到正目录。
            val error = expectIOException {
                ModelDownloader.replaceModelDirectory(staging, model, failCallsRename(setOf(2)))
            }
            val message = error.message ?: ""
            assertTrue(message.contains("Failed to move new model into place"))
            assertTrue(message.contains("old model restored"))
            assertModelContents(model, "old")
            assertFalse(backupOf(root).exists())
            assertTrue("failed staging is installModel's job to clean, not replace's", staging.exists())
        }
    }

    @Test
    fun rollbackFailure_keepsBackup_nextInstallSelfHeals() {
        runWithTempDir { root ->
            val model = modelDir(root)
            val staging = File(root, "staging")
            writeModel(model, "old")
            writeModel(staging, "new")
            // 调用序：1 让位（真实成功）、2 就位（注入失败）、3 回滚（注入失败）。
            val error = expectIOException {
                ModelDownloader.replaceModelDirectory(staging, model, failCallsRename(setOf(2, 3)))
            }
            val message = error.message ?: ""
            assertTrue(message.contains("failed to restore old model"))
            // 本分支文案为 "backup=$backup kept for next install"（备份路径插在
            // backup 与 kept 之间），故断言 "kept for next install" 而非
            // 另一分支的 "backup kept" 原样短语；语义同为“备份保留待下次自愈”。
            // 回滚失败后：旧模型留在备份里、正目录缺失——正是下次安装自愈分支的入口。
            assertModelContents(backupOf(root), "old")
            assertFalse(model.exists())
            // 下次安装（真实 rename）：自愈恢复备份→让位→新模型就位→删备份，全程成功。
            val staging2 = File(root, "staging2")
            writeModel(staging2, "new2")
            ModelDownloader.replaceModelDirectory(staging2, model)
            assertModelContents(model, "new2")
            assertFalse(backupOf(root).exists())
        }
    }

    @Test
    fun staleBackup_withCompleteModel_isRemovedAfterInstall() {
        runWithTempDir { root ->
            val model = modelDir(root)
            val backup = backupOf(root)
            val staging = File(root, "staging")
            writeModel(model, "old")
            writeModel(backup, "older")
            writeModel(staging, "new")
            // 在位模型完整：备份是已被取代的旧模型，安装成功后备份消失。
            ModelDownloader.replaceModelDirectory(staging, model)
            assertModelContents(model, "new")
            assertFalse(backup.exists())
        }
    }

    @Test
    fun incompleteModel_withBackup_restoresThenInstalls() {
        runWithTempDir { root ->
            val model = modelDir(root)
            val backup = backupOf(root)
            val staging = File(root, "staging")
            // 在位模型残缺（只有 tokens）：备份是最后一把好模型，先恢复在位再正常换装，
            // 不能因「并存」就删备份、也不能直接 abort 让用户卡死。
            assertTrue(model.mkdirs())
            File(model, "tokens.txt").writeText("broken")
            writeModel(backup, "old")
            writeModel(staging, "new")
            ModelDownloader.replaceModelDirectory(staging, model)
            assertModelContents(model, "new")
            assertFalse(backup.exists())
        }
    }

    @Test
    fun crossDeviceStagingRenameFailure_rollsBackWithRealRename() {
        runWithTempDir { root ->
            val shm = File("/dev/shm")
            assumeTrue("cross-device test needs a writable /dev/shm", shm.isDirectory && shm.canWrite())
            // 探针：先证实本环境下跨设备 rename 确实失败，否则跳过（不谎报失败）。
            val probeSrc = Files.createTempDirectory(shm.toPath(), "probe-").toFile()
            try {
                File(probeSrc, "keep.txt").writeText("probe")
                val probeDst = File(root, "probe-dst")
                assumeTrue("probe rename must fail across devices", !probeSrc.renameTo(probeDst))
            } finally {
                probeSrc.deleteRecursively()
            }
            val model = modelDir(root)
            val staging = Files.createTempDirectory(shm.toPath(), "staging-").toFile()
            try {
                writeModel(model, "old")
                writeModel(staging, "new")
                // staging 在 /dev/shm、模型在 root 所在设备：真实 renameTo 跨设备必败，
                // 走就位失败→回滚成功分支，全程不用注入缝。
                val error = expectIOException {
                    ModelDownloader.replaceModelDirectory(staging, model)
                }
                val message = error.message ?: ""
                assertTrue(message.contains("Failed to move new model into place"))
                assertTrue(message.contains("old model restored"))
                assertModelContents(model, "old")
                assertFalse(backupOf(root).exists())
            } finally {
                staging.deleteRecursively()
            }
        }
    }
}
