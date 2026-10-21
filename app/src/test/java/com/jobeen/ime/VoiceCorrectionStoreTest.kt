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
        // 两字词对单次观察只进观察期：不生效；观察计数以 O 行落盘
        // （P3 起观察期持久化，旧口径「观察不落盘」已废止），但
        // 不得出现该词对的 P 行（未生效不落正式规则）
        store.learn("开会", "开汇")
        assertEquals("去开会", store.correct("去开会").text)
        awaitContent(file, "O\t开会\t开汇\t1")
        assertTrue(!file.readText(Charsets.UTF_8).contains("P\t开汇\t开会"))
        // 第二次独立观察才正式生效
        store.learn("开会", "开汇")
        assertEquals("去开汇", store.correct("去开会").text)
        awaitContent(file, "P\t开汇\t开会")
        // 生效后观察记录已摘除，落盘文件里不再有该词对的 O 行
        awaitCondition(file) { !it.contains("O\t开会\t开汇") }
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

    // ── P3：规则生命周期（命中淘汰/正向确认/观察期落盘） ──

    /** 从落盘内容里取某词对 P 行的列（正形、错形定位）。 */
    private fun pRow(content: String, right: String, wrong: String): List<String>? =
        content.lineSequence()
            .map { it.split('\t') }
            .firstOrNull { it.size >= 3 && it[0] == "P" && it[1] == right && it[2] == wrong }

    /** 预置文件的词对行：六列全格式，时间/命中按入参。 */
    private fun pLine(right: String, wrong: String, hit: Int, lastHit: Long, learned: Long) =
        "P\t$right\t$wrong\t$hit\t$lastHit\t$learned\n"

    @Test
    fun hitOldPairSurvivesCapEviction() {
        val file = tempFile()
        // 预置 301 条（超上限 1）：第 0 条命中过且最近命中时间最新，
        // 其余从未命中、学入时间相同——旧口径按学入顺序会挤掉第 0
        // 条，新口径必须保住它、淘汰未命中者中学入最靠前的一条
        val sb = StringBuilder()
        for (i in 0 until VoiceCorrectionStore.MAX_PAIRS + 1) {
            if (i == 0) {
                sb.append(pLine(rightForm(0), wrongForm(0), hit = 5, lastHit = 2_000_000, learned = 1_000_000))
            } else {
                sb.append(pLine(rightForm(i), wrongForm(i), hit = 0, lastHit = 0, learned = 1_000_000))
            }
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
        val store = VoiceCorrectionStore(file)
        store.rules() // 触发加载与就地淘汰
        assertEquals(rightForm(0), store.correct(wrongForm(0)).text)
        assertEquals(wrongForm(1), store.correct(wrongForm(1)).text)
        assertEquals(
            rightForm(VoiceCorrectionStore.MAX_PAIRS),
            store.correct(wrongForm(VoiceCorrectionStore.MAX_PAIRS)).text
        )
    }

    @Test
    fun unhitNewPairEvictedBeforeHitOldPair() {
        val file = tempFile()
        // 老词对 A：学入最早，但命中过（活动时间 5000＋命中加
        // 成）；新词对 B：学入较晚、从未命中（分数=学入 4000）；
        // filler 分数 9000 起全部安全。超限 1 条时应淘汰分数最
        // 低的 B，而不是学入顺序最靠前的 A
        val sb = StringBuilder()
        sb.append(pLine(rightForm(0), wrongForm(0), hit = 3, lastHit = 5_000, learned = 1_000))
        sb.append(pLine(rightForm(1), wrongForm(1), hit = 0, lastHit = 0, learned = 4_000))
        for (i in 2 until VoiceCorrectionStore.MAX_PAIRS + 1) {
            sb.append(pLine(rightForm(i), wrongForm(i), hit = 0, lastHit = 0, learned = 9_000))
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
        val store = VoiceCorrectionStore(file)
        store.rules()
        assertEquals(rightForm(0), store.correct(wrongForm(0)).text)
        assertEquals(wrongForm(1), store.correct(wrongForm(1)).text)
    }

    @Test
    fun hitCountRecordedOnlyOnActualReplacement() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        awaitContent(file, "P\t星辰海\t星辰大")
        // 仅路过未替换：applied 为空，无可记账，计数保持 0
        val noHit = store.correct("今天开会")
        assertTrue(noHit.applied.isEmpty())
        var row = pRow(file.readText(Charsets.UTF_8), "星辰海", "星辰大")!!
        assertEquals("0", row[3])
        // 实际替换但调用方未记账：correct 只算不记（L3，被封锁/
        // 未落屏的结果走的正是这条），计数仍为 0
        val result = store.correct("去星辰大")
        assertEquals("去星辰海", result.text)
        assertTrue(result.applied.isNotEmpty())
        row = pRow(file.readText(Charsets.UTF_8), "星辰海", "星辰大")!!
        assertEquals("0", row[3])
        // 调用方在落屏成立后显式记账：计数变 1、最近命中时间写入
        store.recordHits(result.applied)
        awaitCondition(file) {
            pRow(it, "星辰海", "星辰大")?.get(3) == "1"
        }
        row = pRow(file.readText(Charsets.UTF_8), "星辰海", "星辰大")!!
        assertTrue(row[4].toLong() > 0)
    }

    @Test
    fun generalizedHitRefreshesGroupLastHit() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("星辰大", "星辰海")
        store.learn("衡辰海", "星辰海")
        awaitContent(file, "P\t星辰海\t衡辰海")
        // 「衡辰大」是字位泛化组合（没被逐字教过）：落屏后记账，
        // 整组两条的最近命中时间都被刷新，但命中次数不虚增
        val result = store.correct("衡辰大")
        assertEquals("星辰海", result.text)
        store.recordHits(result.applied)
        awaitCondition(file) {
            val r1 = pRow(it, "星辰海", "星辰大")
            val r2 = pRow(it, "星辰海", "衡辰海")
            r1 != null && r2 != null && r1[4].toLong() > 0 && r2[4].toLong() > 0
        }
        val row = pRow(file.readText(Charsets.UTF_8), "星辰海", "星辰大")!!
        assertEquals("0", row[3])
    }

    @Test
    fun probationPersistsAcrossInstances() {
        val file = tempFile()
        val first = VoiceCorrectionStore(file)
        first.learn("开会", "开汇")
        awaitContent(file, "O\t开会\t开汇\t1")
        // 模拟进程重启：新实例从落盘的 O 行恢复观察计数，第二次
        // 观察即正式生效（旧口径计数在内存、重启后还需再观察两次）
        val second = VoiceCorrectionStore(file)
        second.learn("开会", "开汇")
        assertEquals("去开汇", second.correct("去开会").text)
        awaitContent(file, "P\t开汇\t开会")
    }

    @Test
    fun clearAlsoClearsPersistedProbation() {
        val file = tempFile()
        val store = VoiceCorrectionStore(file)
        store.learn("开会", "开汇")
        awaitContent(file, "O\t开会\t开汇\t1")

        store.clear()
        awaitCondition(file) { !it.contains("O\t") }
        // 清除后计数从头来：一次观察仍不生效
        val reloaded = VoiceCorrectionStore(file)
        reloaded.learn("开会", "开汇")
        assertEquals("去开会", reloaded.correct("去开会").text)
    }

    @Test
    fun legacyAndMalformedMetaLoadWithDefaults() {
        val file = tempFile()
        file.writeText(
            // 旧版三列 P 行
            "P\t星辰海\t星辰大\n" +
                // 六列但元信息全坏：词对保留、数值按 0
                "P\t衡辰海\t衡辰大\tX\tY\tZ\n" +
                // 坏行型继续逐行跳过
                "O\t只有两列\n" +
                "O\t星\t星辰海\t1\n", // 非两字对，非法 O 行
            Charsets.UTF_8
        )
        val store = VoiceCorrectionStore(file)
        val firstHit = store.correct("星辰大")
        assertEquals("星辰海", firstHit.text)
        store.recordHits(firstHit.applied)
        assertEquals("衡辰海", store.correct("衡辰大").text)
        // 坏数值不连坐：触发一次落盘后两条都在，命中列按缺省与实记
        store.learn("星辰峰", "星辰海")
        awaitContent(file, "P\t星辰海\t星辰峰")
        val row = pRow(file.readText(Charsets.UTF_8), "星辰海", "星辰大")!!
        assertEquals(6, row.size)
        assertEquals("1", row[3]) // 上面落屏后显式记账过一次
    }

    // ── 第十三批：淘汰复合分数（L2）与时间戳口径（L5） ──

    @Test
    fun highHitOldPairSurvivesEvictionByScoreBonus() {
        val file = tempFile()
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        // 高频老规则：60 天前最后命中、命中 80 次（加成约 6 周，
        // 分数≈18 天前活动）；新学未命中规则：20 天前学入（分数
        // 即 20 天前，全场最低）；filler：10 天前学入（分数更
        // 高、安全）。超限 1 条时，纯活动时间口径会先淘汰高频
        // 老规则，复合分数口径应淘汰分数最低的新学未命中规则、
        // 保住老规则
        val sb = StringBuilder()
        sb.append(pLine(rightForm(0), wrongForm(0), hit = 80, lastHit = now - 60 * day, learned = now - 90 * day))
        sb.append(pLine(rightForm(1), wrongForm(1), hit = 0, lastHit = 0, learned = now - 20 * day))
        for (i in 2 until VoiceCorrectionStore.MAX_PAIRS + 1) {
            sb.append(pLine(rightForm(i), wrongForm(i), hit = 0, lastHit = 0, learned = now - 10 * day))
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
        val store = VoiceCorrectionStore(file)
        store.rules() // 触发加载与就地淘汰
        assertEquals(rightForm(0), store.correct(wrongForm(0)).text)
        assertEquals(wrongForm(1), store.correct(wrongForm(1)).text)
    }

    @Test
    fun legacyRowsGetLoadTimeAsLearnedAt() {
        val file = tempFile()
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        // 一条六列行（10 天前学入、从未命中）＋ 300 条旧三列行：
        // 三列行学入时间记为加载时刻后，活动时间最低的是那条
        // 六列行，超限 1 条时应淘汰它；若三列行仍按时间 0 读
        // 入，被淘汰的会是某条三列行、六列行存活
        val sb = StringBuilder()
        sb.append(pLine(rightForm(0), wrongForm(0), hit = 0, lastHit = 0, learned = now - 10 * day))
        for (i in 1 until VoiceCorrectionStore.MAX_PAIRS + 1) {
            sb.append("P\t${rightForm(i)}\t${wrongForm(i)}\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
        val store = VoiceCorrectionStore(file)
        store.rules()
        assertEquals(wrongForm(0), store.correct(wrongForm(0)).text)
        assertEquals(
            rightForm(VoiceCorrectionStore.MAX_PAIRS),
            store.correct(wrongForm(VoiceCorrectionStore.MAX_PAIRS)).text
        )
    }

    @Test
    fun futureTimestampsClampedOnLoad() {
        val file = tempFile()
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        // 时钟曾被调到未来时写下的时间戳：原样读入会让该词对的
        // 活动时间永远最大、永不淘汰；加载须钳到加载时刻
        file.writeText(
            pLine(rightForm(0), wrongForm(0), hit = 2, lastHit = now + 365 * day, learned = now + 365 * day),
            Charsets.UTF_8
        )
        val store = VoiceCorrectionStore(file)
        store.rules()
        // 触发一次落盘，读回该行的时间戳断言已被钳制（仍 >0，
        // 是钳到加载时刻而非清零）
        store.learn("星辰峰", "星辰海")
        awaitContent(file, "P\t星辰海\t星辰峰")
        val row = pRow(file.readText(Charsets.UTF_8), rightForm(0), wrongForm(0))!!
        val after = System.currentTimeMillis()
        assertTrue(row[4].toLong() in 1..after)
        assertTrue(row[5].toLong() in 1..after)
    }
}
