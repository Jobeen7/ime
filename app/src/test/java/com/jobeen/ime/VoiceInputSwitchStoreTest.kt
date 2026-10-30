// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceInputSwitchStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音输入总开关存储：默认开、持久化、损坏默认开、单实例。 */
class VoiceInputSwitchStoreTest {

    @Test
    fun defaultOn_whenFileMissing() {
        val dir = Files.createTempDirectory("voice-switch-test").toFile()
        try {
            val store = VoiceInputSwitchStore(File(dir, VoiceInputSwitchStore.FILE_NAME))
            assertTrue(store.enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setFalse_persistsAcrossInstances() {
        val dir = Files.createTempDirectory("voice-switch-test").toFile()
        try {
            val file = File(dir, VoiceInputSwitchStore.FILE_NAME)
            VoiceInputSwitchStore(file).setEnabled(false)
            assertFalse(VoiceInputSwitchStore(file).enabled)
            VoiceInputSwitchStore(file).setEnabled(true)
            assertTrue(VoiceInputSwitchStore(file).enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptContent_defaultsOn() {
        val dir = Files.createTempDirectory("voice-switch-test").toFile()
        try {
            val file = File(dir, VoiceInputSwitchStore.FILE_NAME)
            file.writeText("xyz")
            assertTrue(VoiceInputSwitchStore(file).enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shared_sameInstanceForSamePath() {
        val dir = Files.createTempDirectory("voice-switch-test").toFile()
        try {
            val file = File(dir, VoiceInputSwitchStore.FILE_NAME)
            assertSame(
                VoiceInputSwitchStore.shared(file),
                VoiceInputSwitchStore.shared(file),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
