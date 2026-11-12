package com.jobeen.ime.base.speech

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-L11：回落写盘失败时内存与磁盘不分叉——内存保持旧值，修好后可重试。 */
class VoiceInputSwitchStoreTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("voice-switch-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun setEnabled_persistsAndReadsBack() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            store.setEnabled(false)
            assertFalse(store.enabled)
            assertEquals("0", file.readText())
            // 新实例从磁盘读回
            assertFalse(VoiceInputSwitchStore(file).enabled)
            store.setEnabled(true)
            assertTrue(store.enabled)
            assertEquals("1", file.readText())
        }
    }

    @Test
    fun persistFailure_keepsOldValueInMemory_noTmpResidue() {
        runWithTempDir { root ->
            // 父路径是个普通文件：任何写盘都失败
            val blocker = File(root, "blocker")
            blocker.writeText("not a dir")
            val file = File(blocker, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            store.setEnabled(false)
            // 写盘失败：内存仍是旧值（开），磁盘无文件
            assertTrue(store.enabled)
            assertFalse(file.exists())
            assertFalse(File(blocker, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())

            // 修好磁盘：同一实例重试即成功，内存与磁盘一致翻转
            assertTrue(blocker.delete())
            assertTrue(blocker.mkdirs())
            store.setEnabled(false)
            assertFalse(store.enabled)
            assertEquals("0", file.readText())
            assertFalse(File(blocker, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
        }
    }

    @Test
    fun corruptedFile_defaultsToEnabled() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            file.writeText("garbage")
            assertTrue(VoiceInputSwitchStore(file).enabled)
        }
    }
}
