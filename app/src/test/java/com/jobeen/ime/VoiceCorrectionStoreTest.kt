package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceCorrectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceCorrectionStoreTest {

    private fun tempFile(): File {
        val f = File.createTempFile("voice-corrections", ".tsv")
        f.delete()
        return f
    }

    /** 落盘走内部单线程执行器，轮询等内容出现。 */
    private fun awaitContent(file: File, needle: String, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.readText(Charsets.UTF_8).contains(needle)) return
            Thread.sleep(50)
        }
        assertTrue("等待落盘超时：$needle", false)
    }

    @Test
    fun learn_correctsAndPersistsAcrossInstances() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("恒星大", "恒星达")
        assertEquals("去恒星达签约", store.correct("去恒星大签约").text)

        awaitContent(file, "P\t恒星达\t恒星大")
        val reloaded = VoiceCorrectionStore(file)
        assertEquals("去恒星达签约", reloaded.correct("去恒星大签约").text)
    }

    @Test
    fun disable_blocksCorrectionAndPersists() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("恒星大", "恒星达")
        store.disable("恒星达")
        assertEquals("恒星大", store.correct("恒星大").text)
        assertTrue(store.isDisabled("恒星达"))

        awaitContent(file, "D\t恒星达")
        val reloaded = VoiceCorrectionStore(file)
        assertEquals("恒星大", reloaded.correct("恒星大").text)
        assertTrue(reloaded.isDisabled("恒星达"))
    }

    @Test
    fun learnReenablesDisabled() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.disable("恒星达")
        store.learn("恒星大", "恒星达")
        assertTrue(!store.isDisabled("恒星达"))
        assertEquals("恒星达", store.correct("恒星大").text)
    }

    @Test
    fun corruptLinesAreIgnored() {
        val file = tempFile()
        file.writeText(
            "garbage line\n" +
                "P\t恒星达\t恒星大\n" +
                "X\t恒星达\t恒星大\n" +
                "P\t只有两列\n" +
                "P\t恒星达\t恒星达\n" + // 错形等于正形，无效
                "D\t陈晓峰\n",
            Charsets.UTF_8
        )
        val store = VoiceCorrectionStore(file)
        assertEquals("恒星达", store.correct("恒星大").text)
        assertTrue(store.isDisabled("陈晓峰"))
    }

    @Test
    fun invalidPairsAreNotLearned() {
        val store = VoiceCorrectionStore(tempFile())
        store.learn("A星达", "恒星达")
        store.learn("恒", "恒星达")
        store.learn("恒星达", "恒星达")
        assertTrue(store.rules().isEmpty())
    }

    @Test
    fun wrongFormBelongsToLatestRightForm() {
        // 同一错形先后被改到两个正形：以最新为准，旧规则不再抢
        val store = VoiceCorrectionStore(tempFile())
        store.learn("恒星大", "恒星达")
        assertEquals("恒星达", store.correct("恒星大").text)
        store.learn("恒星大", "衡星达")
        assertEquals("衡星达", store.correct("恒星大").text)
    }

    @Test
    fun positionalVariantsGeneralizeAcrossPairs() {
        val store = VoiceCorrectionStore(tempFile())
        store.learn("恒星大", "恒星达")
        store.learn("衡星达", "恒星达")
        assertEquals("恒星达", store.correct("衡星大").text)
    }
}
