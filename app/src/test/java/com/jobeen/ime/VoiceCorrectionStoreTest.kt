package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceCorrectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 轮询等落盘内容满足条件（用于清除/覆写类断言）。 */
    private fun awaitCondition(file: File, timeoutMs: Long = 3000, check: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && check(file.readText(Charsets.UTF_8))) return
            Thread.sleep(50)
        }
        assertTrue("等待落盘条件超时", false)
    }

    @Test
    fun learn_correctsAndPersistsAcrossInstances() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        assertEquals("去星辰海签约", store.correct("去星辰大签约").text)

        awaitContent(file, "P\t星辰海\t星辰大")
        val reloaded = VoiceCorrectionStore(file)
        assertEquals("去星辰海签约", reloaded.correct("去星辰大签约").text)
    }

    @Test
    fun twoCharPair_needsTwoObservations() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        // 两字词对单次观察只进观察期：不生效、也不落盘
        store.learn("开会", "开汇")
        assertEquals("去开会", store.correct("去开会").text)
        assertTrue(!file.exists() || !file.readText(Charsets.UTF_8).contains("开汇"))
        // 第二次独立观察才正式生效
        store.learn("开会", "开汇")
        assertEquals("去开汇", store.correct("去开会").text)
        awaitContent(file, "P\t开汇\t开会")
    }

    @Test
    fun twoCharPair_probationIsPerPair() {
        val store = VoiceCorrectionStore(tempFile())
        // 观察计数按词对隔离：另一个词对的观察不攒数
        store.learn("开会", "开汇")
        store.learn("大海", "大嗨")
        assertEquals("去开会", store.correct("去开会").text)
        assertEquals("看大海", store.correct("看大海").text)
    }

    @Test
    fun disable_blocksCorrectionAndPersists() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        store.disable("星辰海")
        assertEquals("星辰大", store.correct("星辰大").text)
        assertTrue(store.isDisabled("星辰海"))

        awaitContent(file, "D\t星辰海")
        val reloaded = VoiceCorrectionStore(file)
        assertEquals("星辰大", reloaded.correct("星辰大").text)
        assertTrue(reloaded.isDisabled("星辰海"))
    }

    @Test
    fun learnReenablesDisabled() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.disable("星辰海")
        store.learn("星辰大", "星辰海")
        assertTrue(!store.isDisabled("星辰海"))
        assertEquals("星辰海", store.correct("星辰大").text)
    }

    @Test
    fun corruptLinesAreIgnored() {
        val file = tempFile()
        file.writeText(
            "garbage line\n" +
                "P\t星辰海\t星辰大\n" +
                "X\t星辰海\t星辰大\n" +
                "P\t只有两列\n" +
                "P\t星辰海\t星辰海\n" + // 错形等于正形，无效
                "D\t星辰峰\n",
            Charsets.UTF_8
        )
        val store = VoiceCorrectionStore(file)
        assertEquals("星辰海", store.correct("星辰大").text)
        assertTrue(store.isDisabled("星辰峰"))
    }

    @Test
    fun invalidPairsAreNotLearned() {
        val store = VoiceCorrectionStore(tempFile())
        store.learn("A星海", "星辰海")
        store.learn("星", "星辰海")
        store.learn("星辰海", "星辰海")
        assertTrue(store.rules().isEmpty())
    }

    @Test
    fun wrongFormBelongsToLatestRightForm() {
        // 同一错形先后被改到两个正形：以最新为准，旧规则不再抢
        val store = VoiceCorrectionStore(tempFile())
        store.learn("星辰大", "星辰海")
        assertEquals("星辰海", store.correct("星辰大").text)
        store.learn("星辰大", "衡辰海")
        assertEquals("衡辰海", store.correct("星辰大").text)
    }

    @Test
    fun positionalVariantsGeneralizeAcrossPairs() {
        val store = VoiceCorrectionStore(tempFile())
        store.learn("星辰大", "星辰海")
        store.learn("衡辰海", "星辰海")
        assertEquals("星辰海", store.correct("衡辰大").text)
    }

    // ── 上限就地生效 ──

    // 造词工具：正形取两池组合（互不相交，保证错形不与任何正形重合）
    private val rightHeads = "星辰海天山川云月风雪雨晴岚峰岛原野泽光霜"
    private val wrongTails = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉"

    // 三字形：两字词对有观察期（学一次不生效），会干扰上限淘汰的验证；
    // 上限语义与词长无关，造词统一用三字（前两字保证 305 条内互不相同）
    private fun rightForm(i: Int): String =
        "${rightHeads[i / 20]}${rightHeads[i % 20]}${rightHeads[(i * 7) % 20]}"

    private fun wrongForm(i: Int): String =
        "${rightHeads[i / 20]}${wrongTails[i % 20]}${wrongTails[(i * 7) % 20]}"

    @Test
    fun pairCapEvictsOldestInMemory() {
        val store = VoiceCorrectionStore(tempFile())
        val total = VoiceCorrectionStore.MAX_PAIRS + 5
        for (i in 0 until total) {
            store.learn(wrongForm(i), rightForm(i))
        }
        // 最旧的 5 条已被就地淘汰，纠不出来；最新的仍生效
        assertEquals(wrongForm(0), store.correct(wrongForm(0)).text)
        assertEquals(wrongForm(4), store.correct(wrongForm(4)).text)
        assertEquals(rightForm(total - 1), store.correct(wrongForm(total - 1)).text)
    }

    @Test
    fun disabledCapEvictsOldestInMemory() {
        val store = VoiceCorrectionStore(tempFile())
        val total = VoiceCorrectionStore.MAX_DISABLED + 5
        for (i in 0 until total) {
            store.disable(rightForm(i))
        }
        assertFalse(store.isDisabled(rightForm(0)))
        assertFalse(store.isDisabled(rightForm(4)))
        assertTrue(store.isDisabled(rightForm(total - 1)))
    }

    // ── 存储可靠性 ──

    @Test
    fun loadFailureDisablesWrites() {
        // 路径被目录占据：读整体失败，本进程内不得落盘覆写
        val file = tempFile()
        assertTrue(file.mkdir())
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        store.disable("星辰峰")
        Thread.sleep(400) // 若有落盘尝试，给执行器时间跑完
        assertTrue("读失败时不得覆写原路径", file.isDirectory)
        assertFalse(
            "读失败时不得产生 tmp 文件",
            File(file.parentFile, file.name + ".tmp").exists()
        )
    }

    @Test
    fun persistedFileContainsNoTmpResidue() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        awaitContent(file, "P\t星辰海\t星辰大")
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    // ── 开关与清除 ──

    @Test
    fun disabledStorePassesThroughAndPersists() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        store.setEnabled(false)
        assertFalse(store.enabled)
        // 关时 correct 直通
        assertEquals("星辰大", store.correct("星辰大").text)

        awaitContent(file, "E\t0")
        val reloaded = VoiceCorrectionStore(file)
        assertFalse(reloaded.enabled)
        assertEquals("星辰大", reloaded.correct("星辰大").text)

        store.setEnabled(true)
        assertEquals("星辰海", store.correct("星辰大").text)
    }

    @Test
    fun clearEmptiesPairsAndFile() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        store.disable("星辰峰")
        awaitContent(file, "P\t星辰海\t星辰大")

        store.clear()
        assertTrue(store.rules().isEmpty())
        assertFalse(store.isDisabled("星辰峰"))
        assertEquals("星辰大", store.correct("星辰大").text)
        awaitCondition(file) { !it.contains("P\t") && !it.contains("D\t") }
    }
}
