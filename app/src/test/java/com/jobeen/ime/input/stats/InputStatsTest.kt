package com.jobeen.ime.input.stats

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入统计（2026-10-29 item-1 C）：计数、周期、段位、导入导出。 */
class InputStatsTest {

    private fun wallMs(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun setup(nowWall: Long, nowClock: Long) {
        InputStats.resetForTest()
        var wall = nowWall
        var clock = nowClock
        InputStats.wallMs = { wall }
        InputStats.clockMs = { clock }
    }

    @Test
    fun countsCommitsCharsKeypressesAndSchemeBucket() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.setScheme("wanxiang")
        InputStats.noteKeyPress(KeyboardAction.SpaceAction)
        InputStats.noteCommit("你好")
        val stats = InputStats.snapshot(InputStats.Period.DAY)
        assertEquals(1L, stats.total.commits)
        assertEquals(2L, stats.total.chars)
        assertEquals(1L, stats.total.keypresses)
        assertEquals(1L, stats.categories["SPACE"])
        assertEquals(2L, stats.schemes["wanxiang"]!!.chars)
        assertEquals(listOf("wanxiang"), InputStats.schemeIds(InputStats.Period.DAY))
    }

    @Test
    fun deletesCountAndAccuracyIsInferred() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.noteCommit("你好世界")
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction)
        val stats = InputStats.snapshot(InputStats.Period.DAY)
        assertEquals(1L, stats.total.deletes)
        // 推断准确率 = 4 / (4 + 1) = 0.8
        assertEquals(0.8, stats.accuracy!!, 0.001)
    }

    @Test
    fun voiceDragProbesNotCounted() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.noteKeyPress(KeyboardAction.VoiceDragPosition(1, 0f, 0f))
        InputStats.noteKeyPress(KeyboardAction.VoiceDragUp)
        assertEquals(0L, InputStats.snapshot(InputStats.Period.DAY).total.keypresses)
    }

    @Test
    fun typingSpeedFromCappedGaps() {
        InputStats.resetForTest()
        var clock = 1000L
        InputStats.clockMs = { clock }
        InputStats.wallMs = { wallMs(2026, 10, 29) }
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("a"))
        clock = 2000L // 间隔 1000ms，计入打字时长
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("b"))
        clock = 2000L + 30_000L // 间隔 30s，超 5s 上限，不计
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("c"))
        InputStats.noteCommit("一二三四五六七八九十") // 10 字
        val stats = InputStats.snapshot(InputStats.Period.DAY)
        assertEquals(1000L, stats.total.typingMs)
        // 10 字 / 1000ms = 600 字／分钟
        assertEquals(600.0, stats.avgSpeed!!, 0.001)
    }

    @Test
    fun monthAggregatesAcrossDaysButDayDoesNot() {
        InputStats.resetForTest()
        InputStats.wallMs = { wallMs(2026, 10, 29) }
        InputStats.clockMs = { 1000L }
        InputStats.noteCommit("甲")
        InputStats.wallMs = { wallMs(2026, 10, 15) }
        InputStats.noteCommit("乙丙")
        val month = InputStats.snapshot(InputStats.Period.MONTH)
        assertEquals(3L, month.total.chars)
        InputStats.wallMs = { wallMs(2026, 10, 29) }
        val day = InputStats.snapshot(InputStats.Period.DAY)
        assertEquals(1L, day.total.chars) // 当日（29 日）只有“甲”
    }

    @Test
    fun rankFromLifetimeAndClearKeepsRank() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.noteCommit("x".repeat(600))
        val rank = InputStats.rank()
        // 五阶（2026-10-29 Lee 决定）：600 字仍在炼气期（0–1999）。
        assertEquals("炼气期", rank.name)
        assertEquals(600L, rank.xp)
        InputStats.clearPeriods()
        assertEquals(0L, InputStats.snapshot(InputStats.Period.DAY).total.chars)
        // 清统计只清周期数据，终身字数与段位保留
        assertEquals("炼气期", InputStats.rank().name)
        assertEquals(600L, InputStats.rank().xp)
    }

    @Test
    fun rankFiveStageBoundaries() {
        setup(wallMs(2026, 10, 29), 1000L)
        assertEquals("炼气期", InputStats.rank().name)
        assertEquals(1, InputStats.rank().level + 1)
        InputStats.noteCommit("x".repeat(1000))
        val mid = InputStats.rank()
        assertEquals("炼气期", mid.name)
        assertEquals(0.5, mid.progress, 0.001)
        InputStats.noteCommit("x".repeat(1000))
        assertEquals("筑基期", InputStats.rank().name)
        InputStats.noteCommit("x".repeat(6000))
        assertEquals("金丹期", InputStats.rank().name)
        InputStats.noteCommit("x".repeat(12000))
        assertEquals("元婴期", InputStats.rank().name)
        InputStats.noteCommit("x".repeat(130000))
        val top = InputStats.rank()
        assertEquals("化神期", top.name)
        assertEquals(1.0, top.progress, 0.001)
        assertNull(top.nextThreshold)
    }

    @Test
    fun heatKeysMappedOnlyWithinGrid() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("q"))
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("Q"))
        InputStats.noteKeyPress(KeyboardAction.SpaceAction)
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction)
        InputStats.noteKeyPress(KeyboardAction.CapsAction)
        InputStats.noteKeyPress(KeyboardAction.LangSwitchAction)
        // 非网格键不记热力（但仍记按键数与类别）。
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("1"))
        InputStats.noteKeyPress(KeyboardAction.LayoutSwitchAction("symbol"))
        val heat = InputStats.snapshot(InputStats.Period.DAY).heat
        assertEquals(2L, heat["q"])
        assertEquals(1L, heat["空格"])
        assertEquals(1L, heat["退格"])
        assertEquals(1L, heat["shift"])
        assertEquals(1L, heat["中文"])
        assertNull(heat["1"])
        assertNull(heat["符号"])
        assertEquals(8L, InputStats.snapshot(InputStats.Period.DAY).total.keypresses)
    }

    @Test
    fun clearFastestOnlyAdvancesBaseline() {
        InputStats.resetForTest()
        var clock = 1000L
        InputStats.clockMs = { clock }
        InputStats.wallMs = { wallMs(2026, 10, 29) }
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("a"))
        clock = 2000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("b"))
        InputStats.noteCommit("x".repeat(60))
        assertEquals(3600.0, InputStats.fastestDaySpeed()!!, 0.001)
        // 清除极速：当日被基线排除，最快为空；但周期字数不动。
        InputStats.clearFastest()
        assertNull(InputStats.fastestDaySpeed())
        assertEquals(60L, InputStats.snapshot(InputStats.Period.MONTH).total.chars)
        // 次日新单日重新参评（首按间隔超窗，不把前日间隙计入当日时长）。
        InputStats.wallMs = { wallMs(2026, 10, 30) }
        clock = 32000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("a"))
        clock = 33000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("b"))
        InputStats.noteCommit("x".repeat(50))
        assertEquals(3000.0, InputStats.fastestDaySpeed()!!, 0.001)
        // 基线随导出／导入保留。
        val json = InputStats.exportJson()
        InputStats.resetForTest()
        assertTrue(InputStats.importJson(json))
        assertEquals(3000.0, InputStats.fastestDaySpeed()!!, 0.001)
    }

    @Test
    fun exportImportRoundTripAndInvalidRejected() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.noteCommit("你好")
        val json = InputStats.exportJson()
        InputStats.resetForTest()
        assertEquals(0L, InputStats.snapshot(InputStats.Period.DAY).total.chars)
        assertTrue(InputStats.importJson(json))
        assertEquals(2L, InputStats.snapshot(InputStats.Period.DAY).total.chars)
        assertFalse(InputStats.importJson("{broken"))
        assertFalse(InputStats.importJson("""{"version":2,"lifetimeChars":0,"days":{}}"""))
        // 非法导入不改动现有数据
        assertEquals(2L, InputStats.snapshot(InputStats.Period.DAY).total.chars)
    }

    @Test
    fun fastestDaySpeedNeedsMinChars() {
        InputStats.resetForTest()
        var clock = 1000L
        InputStats.clockMs = { clock }
        InputStats.wallMs = { wallMs(2026, 10, 29) }
        // 1 字／1000ms = 60 字／分钟，但字数 < 50，不参与最快
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("a"))
        clock = 2000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("b"))
        InputStats.noteCommit("一")
        assertNull(InputStats.fastestDaySpeed())
    }

    @Test
    fun commitBucketsByTextLength() {
        setup(wallMs(2026, 10, 29), 1000L)
        InputStats.setScheme("wanxiang")
        InputStats.noteCommit("a") // 1字
        InputStats.noteCommit("你好") // 2字
        InputStats.noteCommit("你好啊") // 3字
        InputStats.noteCommit("你好啊呀") // 4字
        InputStats.noteCommit("你好啊呀哈") // 5字→4字以上
        InputStats.noteCommit("") // 空串不记
        val total = InputStats.snapshot(InputStats.Period.DAY).total
        assertEquals(1L, total.wordLen1)
        assertEquals(1L, total.wordLen2)
        assertEquals(1L, total.wordLen3)
        assertEquals(2L, total.wordLen4plus)
        assertEquals(5L, total.commits)
        // 方案桶同步分档
        val scheme = InputStats.snapshot(InputStats.Period.DAY).schemes["wanxiang"]!!
        assertEquals(1L, scheme.wordLen1)
        assertEquals(2L, scheme.wordLen4plus)
    }

    @Test
    fun correctionCountedOncePerDeleteThenInputAndExpires() {
        InputStats.resetForTest()
        var clock = 1000L
        InputStats.clockMs = { clock }
        InputStats.wallMs = { wallMs(2026, 10, 29) }
        fun total() = InputStats.snapshot(InputStats.Period.DAY).total
        // 删除→1000ms内输入，记1次；同窗内再输入不重复记
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction) // t=1000 删除
        clock = 2000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("a"))
        assertEquals(1L, total().corrections)
        clock = 3000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("b"))
        assertEquals(1L, total().corrections)
        // 删除→回车不消耗→窗内输入，记第2次
        clock = 4000L
        InputStats.noteKeyPress(KeyboardAction.DeleteForwardAction)
        clock = 4500L
        InputStats.noteKeyPress(KeyboardAction.ReturnAction())
        assertEquals(1L, total().corrections)
        clock = 5000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("c"))
        assertEquals(2L, total().corrections)
        // 删除→超窗输入，不记；过期后空格也不记
        clock = 6000L
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction)
        clock = 12000L
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("d"))
        assertEquals(2L, total().corrections)
        clock = 13000L
        InputStats.noteKeyPress(KeyboardAction.SpaceAction)
        assertEquals(2L, total().corrections)
        // 删除→窗内空格记第3次；删除→窗内选词记第4次
        clock = 14000L
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction)
        clock = 14500L
        InputStats.noteKeyPress(KeyboardAction.SpaceAction)
        assertEquals(3L, total().corrections)
        clock = 15000L
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction)
        clock = 15500L
        InputStats.noteKeyPress(
            KeyboardAction.SelectCandidatePinYin(
                com.jobeen.ime.engine.data.CandidatePinYin("ni", "ni", 0),
            ),
        )
        assertEquals(4L, total().corrections)
        assertEquals(13L, total().keypresses)
    }

    @Test
    fun oldJsonWithoutNewFieldsLoadsAsZeroAndRoundTrips() {
        setup(wallMs(2026, 10, 29), 1000L)
        val hours = (0 until 24).joinToString(",") { "\"$it\":0" }
        val oldDay = """{"commits":1,"chars":2,"keypresses":3,"deletes":1,"typingMs":1000,"schemes":{"":{"commits":1,"chars":2,"keypresses":3,"deletes":1,"typingMs":1000}},"hours":{$hours},"categories":{}}"""
        val oldJson = """{"version":1,"lifetimeChars":2,"days":{"20261029":$oldDay}}"""
        assertTrue(InputStats.importJson(oldJson))
        val total = InputStats.snapshot(InputStats.Period.DAY).total
        assertEquals(2L, total.chars)
        // 旧文件无新字段，按 0 读入
        assertEquals(0L, total.wordLen1)
        assertEquals(0L, total.wordLen2)
        assertEquals(0L, total.wordLen3)
        assertEquals(0L, total.wordLen4plus)
        assertEquals(0L, total.corrections)
        // 新数据导出再导入，新字段保留
        InputStats.noteCommit("你好")
        InputStats.noteKeyPress(KeyboardAction.BackspaceAction)
        InputStats.noteKeyPress(KeyboardAction.KeySequenceAction("a"))
        val json = InputStats.exportJson()
        InputStats.resetForTest()
        assertTrue(InputStats.importJson(json))
        val reloaded = InputStats.snapshot(InputStats.Period.DAY).total
        assertEquals(1L, reloaded.wordLen2)
        assertEquals(1L, reloaded.corrections)
    }
}
