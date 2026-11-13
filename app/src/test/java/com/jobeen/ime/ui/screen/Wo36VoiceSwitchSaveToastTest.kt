// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.base.speech.VoiceInputSwitchStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V-低1（WO36）：语音总开关落盘失败时给 toast，不再静默回弹。
 *
 * Compose 页本身 JVM 测不了（无 Robolectric，Toast 调用更测不了），
 * 这里钉的是接线的可测前提——把 VoiceSettingsScreen.onCheckedChange
 * 的本体（除 Toast 调用外）逐行复现为 handleToggle：
 * toast 判定只看 setEnabled 返回值（经 voiceSwitchSaveFailed），
 * UI 值只看重读的 store.enabled（回弹逻辑本身不动）。
 * 每格断言 toast 判定＋UI 值＋磁盘内容三者一致。
 * Toast 真弹出与否、回弹后开关视觉，留待真机验收。
 */
class Wo36VoiceSwitchSaveToastTest {

    /**
     * 与页内 onCheckedChange 同款（除 Toast 调用外）：
     * 返回 toast 是否该弹＋重读后的 UI 值。
     */
    private fun handleToggle(
        store: VoiceInputSwitchStore,
        requested: Boolean,
    ): Pair<Boolean, Boolean> {
        val showToast = voiceSwitchSaveFailed(store.setEnabled(requested))
        return showToast to store.enabled
    }

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("wo36-voice-switch-toast-test-").toFile()
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
    fun successFlipOnToOff_noToast_uiFollowsDiskAgrees() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            val (showToast, uiEnabled) = handleToggle(store, false)
            assertFalse(showToast)
            assertFalse(uiEnabled)
            assertEquals("0", file.readText())
        }
    }

    @Test
    fun successFlipOffToOn_noToast_uiFollowsDiskAgrees() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.setEnabled(false))
            val (showToast, uiEnabled) = handleToggle(store, true)
            assertFalse(showToast)
            assertTrue(uiEnabled)
            assertEquals("1", file.readText())
        }
    }

    @Test
    fun sameValueOn_noToast_noDiskWrite() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            // 已是请求值：无需写盘也返回 true，不 toast、不落盘。
            val (showToast, uiEnabled) = handleToggle(store, true)
            assertFalse(showToast)
            assertTrue(uiEnabled)
            assertFalse(file.exists())
        }
    }

    @Test
    fun sameValueOff_noToast_valueUnchanged() {
        runWithTempDir { root ->
            val file = File(root, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.setEnabled(false))
            assertEquals("0", file.readText())
            val (showToast, uiEnabled) = handleToggle(store, false)
            assertFalse(showToast)
            assertFalse(uiEnabled)
            assertEquals("0", file.readText())
        }
    }

    @Test
    fun persistFailureOnToOff_toastAndRollbackToOn() {
        runWithTempDir { root ->
            val (blocker, file) = blockedFile(root)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.enabled)
            val (showToast, uiEnabled) = handleToggle(store, false)
            // 失败：toast 该弹，UI 重读回滚回旧值（开），磁盘无文件、无 tmp 残留。
            assertTrue(showToast)
            assertTrue(uiEnabled)
            assertFalse(file.exists())
            assertFalse(File(blocker, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
        }
    }

    @Test
    fun persistFailureOffToOn_toastAndRollbackToOff() {
        runWithTempDir { root ->
            val subdir = File(root, "sub")
            assertTrue(subdir.mkdirs())
            val file = File(subdir, VoiceInputSwitchStore.FILE_NAME)
            val store = VoiceInputSwitchStore(file)
            assertTrue(store.setEnabled(false))
            assertFalse(store.enabled)
            // 堵住同一 store 的写盘路：删文件与目录，目录位换成普通文件。
            assertTrue(file.delete())
            assertTrue(subdir.delete())
            subdir.writeText("not a dir")
            val (showToast, uiEnabled) = handleToggle(store, true)
            assertTrue(showToast)
            assertFalse(uiEnabled)
            assertFalse(File(subdir, VoiceInputSwitchStore.FILE_NAME).exists())
            assertFalse(File(subdir, VoiceInputSwitchStore.FILE_NAME + ".tmp").exists())
        }
    }

    @Test
    fun retryAfterRepair_noToast_uiFlips() {
        runWithTempDir { root ->
            val (blocker, file) = blockedFile(root)
            val store = VoiceInputSwitchStore(file)
            val (showToastFirst, uiFirst) = handleToggle(store, false)
            assertTrue(showToastFirst)
            assertTrue(uiFirst)
            assertTrue(blocker.delete())
            assertTrue(blocker.mkdirs())
            val (showToastRetry, uiRetry) = handleToggle(store, false)
            assertFalse(showToastRetry)
            assertFalse(uiRetry)
            assertEquals("0", file.readText())
        }
    }
}
