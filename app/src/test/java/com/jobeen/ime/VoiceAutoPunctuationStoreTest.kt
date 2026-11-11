// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceAutoPunctuationStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音自动加标点开关存储：默认开、持久化、损坏默认开、单实例；
 * 与总开关不同：读值每次现读磁盘（:speech 进程与设置页不同进程，
 * 不缓存才看得到开关变化）。
 */
class VoiceAutoPunctuationStoreTest {

    @Test
    fun defaultOn_whenFileMissing() {
        val dir = Files.createTempDirectory("voice-punc-test").toFile()
        try {
            val store = VoiceAutoPunctuationStore(File(dir, VoiceAutoPunctuationStore.FILE_NAME))
            assertTrue(store.enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setFalse_persistsAcrossInstances() {
        val dir = Files.createTempDirectory("voice-punc-test").toFile()
        try {
            val file = File(dir, VoiceAutoPunctuationStore.FILE_NAME)
            VoiceAutoPunctuationStore(file).setEnabled(false)
            assertFalse(VoiceAutoPunctuationStore(file).enabled)
            VoiceAutoPunctuationStore(file).setEnabled(true)
            assertTrue(VoiceAutoPunctuationStore(file).enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptContent_defaultsOn() {
        val dir = Files.createTempDirectory("voice-punc-test").toFile()
        try {
            val file = File(dir, VoiceAutoPunctuationStore.FILE_NAME)
            file.writeText("xyz")
            assertTrue(VoiceAutoPunctuationStore(file).enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shared_sameInstanceForSamePath() {
        val dir = Files.createTempDirectory("voice-punc-test").toFile()
        try {
            val file = File(dir, VoiceAutoPunctuationStore.FILE_NAME)
            assertSame(
                VoiceAutoPunctuationStore.shared(file),
                VoiceAutoPunctuationStore.shared(file),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun externalWrite_visibleWithoutRecreatingStore() {
        // 现读不缓存：同一实例在外部（设置页进程）改写文件后，
        // 下一次读即见新值——:speech 进程靠这点跟上开关变化。
        val dir = Files.createTempDirectory("voice-punc-test").toFile()
        try {
            val file = File(dir, VoiceAutoPunctuationStore.FILE_NAME)
            val store = VoiceAutoPunctuationStore(file)
            assertTrue(store.enabled)
            file.writeText("0")
            assertFalse(store.enabled)
            file.writeText("1")
            assertTrue(store.enabled)
        } finally {
            dir.deleteRecursively()
        }
    }
}
