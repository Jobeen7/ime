package com.jobeen.ime.base.speech

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-低1：setEnabled 返回“最终值是否等于请求值”。
 * 状态空间分区：成功翻转／同值无操作／写盘失败（开→关、关→开双向）
 * ／失败后修复重试；每格断言返回值＋内存值＋磁盘内容三者一致——
 * 设置页 UI 按返回值／重读 enabled 回写，所见即 store 真值。
 * （B-L11 的内存口径本身不变：失败仍不翻内存，只新增返回值。）
 */
class VoiceInputSwitchStoreSetEnabledResultTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("voice-switch-result-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 父路径是个普通文件：任何写盘都失败（与既有测试同款构造）。 */
    private fun blockedFile(root: File): Pair<File, File> {
        val blocker = File(root, "blocker")
        blocker.writeText("not a dir")
        return blocker to File(blocker, VoiceInputSwitchStore.FILE_NAME)
    }

    @Test
    fun successFlip_returnsTrue_valueAndDiskAgree() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.setEnabled(false))
            assertFalse(store.enabled)
            assertEquals("0", file.readText())
            assertTrue(store.setEnabled(true))
            assertTrue(store.enabled)
            assertEquals("1", file.readText())
        }
    }

    @Test
    fun sameValueNoOp_returnsTrue_valueUnchanged() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            // 已是请求值：无需写盘也返回 true（最终值即请求值）。
            assertTrue(store.setEnabled(true))
            assertTrue(store.enabled)
            assertTrue(store.setEnabled(false))
            assertFalse(store.enabled)
            assertTrue(store.setEnabled(false))
            assertFalse(store.enabled)
            assertEquals("0", file.readText())
        }
    }

    @Test
    fun persistFailure_returnsFalse_oldValueKept_noTmpResidue() {
        runWithTempDir { root ->
            val (blocker, file) = blockedFile(root)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            assertFalse(store.setEnabled(false))
            // 返回 false 且内存仍是旧值（开），磁盘无文件、无 tmp 残留。
            assertTrue(store.enabled)
            assertFalse(file.exists())
            assertFalse(File(blocker, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
            // 再试一次仍 false，不翻转、不留残留。
            assertFalse(store.setEnabled(false))
            assertTrue(store.enabled)
            assertFalse(File(blocker, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
        }
    }

    @Test
    fun persistFailureReverseDirection_returnsFalse_oldValueKept() {
        runWithTempDir { root ->
            // 反向：内存已是关（false），开写盘失败时返回 false 且仍关。
            val subdir = File(root, "sub")
            assertTrue(subdir.mkdirs())
            val file = File(subdir, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.setEnabled(false))
            assertFalse(store.enabled)
            assertEquals("0", file.readText())
            // 堵住同一 store 的写盘路：删文件与目录，目录位换成普通文件。
            assertTrue(file.delete())
            assertTrue(subdir.delete())
            subdir.writeText("not a dir")
            assertFalse(store.setEnabled(true))
            assertFalse(store.enabled)
            assertFalse(File(subdir, VoiceInputSwitchStore.FILE_NAME).exists())
            assertFalse(File(subdir, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
        }
    }

    @Test
    fun retryAfterRepair_returnsTrue() {
        runWithTempDir { root ->
            val (blocker, file) = blockedFile(root)
            val store = VoiceInputSwitchStore(file)
            assertFalse(store.setEnabled(false))
            assertTrue(store.enabled)
            assertTrue(blocker.delete())
            assertTrue(blocker.mkdirs())
            assertTrue(store.setEnabled(false))
            assertFalse(store.enabled)
            assertEquals("0", file.readText())
            assertFalse(File(blocker, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
        }
    }
}
