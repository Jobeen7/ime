package com.jobeen.ime

import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.rime.behavior.InputString
import com.jobeen.ime.engine.rime.behavior.SelectPinYin
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.host.BehaviorHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * WO52 1.5：T9 选词重算（native t9SetPinyin 的 Kotlin 侧等价）。
 *
 * native t9SetPinyin 把“拼音＋'”写回会话输入；Kotlin 侧 build()
 * 必须逐字拼出同一串——下一键重建输入（setInput(build())）与
 * native 写入的串若不一致，队列与会话即脱节。本测试断言 build()
 * 在各分词序列下等于 native 应写的值（含多字母拼音钉住、钉后
 * 再钉、未选尾段保持原始数字）；同时断言拼音列表本身（与
 * PinYinUtil.possibleT9Combinations 同源，主机 C++ 测试用同一
 * 期望矩阵复核 native 展开，两边一致才算对齐）。
 *
 * FakeJob 的 sendJob 是空操作：本测试只验队列/bookkeeping，不
 * 触 native（JVM 上无 .so）。
 */
class T9SetPinyinFlowTest {

    private class FakeJob : IRimeJob {
        override fun sendJob(block: suspend RimeApi.() -> Unit) { /* 只登记队列，不投递引擎 */ }
        override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T =
            defaultValue
    }

    /** 按一串数字键，返回当前段的可选拼音。 */
    private fun BehaviorHost.pressDigits(digits: String): List<CandidatePinYin> {
        digits.forEach { flowed(InputString(it.toString())) }
        return possiblePinYin("T9PinYin", build(), 0)
    }

    /** 从当前列表点选指定拼音，返回点选后的 build。 */
    private fun BehaviorHost.select(pinyin: String): String {
        val options = possiblePinYin("T9PinYin", build(), 0)
        val pick = options.firstOrNull { it.pinYin == pinyin }
        assertNotNull("possible should contain '$pinyin' for input ${build()}", pick)
        flowed(SelectPinYin(pick!!))
        return build()
    }

    @Test
    fun pinyinListFor726() {
        val host = BehaviorHost(FakeJob())
        val options = host.pressDigits("726").map { it.pinYin }
        assertEquals(
            listOf("pan", "pao", "ran", "rao", "san", "sao", "pa", "sa", "p", "q", "r", "s"),
            options,
        )
    }

    @Test
    fun pinyinListFor54() {
        val host = BehaviorHost(FakeJob())
        val options = host.pressDigits("54").map { it.pinYin }
        assertEquals(listOf("ji", "li", "j", "k", "l"), options)
    }

    @Test
    fun pinyinListForSingleDigits() {
        val host = BehaviorHost(FakeJob())
        assertEquals(listOf("a", "b", "c"), host.pressDigits("2").map { it.pinYin })
        val host2 = BehaviorHost(FakeJob())
        // 4：g/h 有拼写，i 无拼写被滤掉
        assertEquals(listOf("g", "h"), host2.pressDigits("4").map { it.pinYin })
        val host3 = BehaviorHost(FakeJob())
        // 8：t 有拼写，u/v 无拼写被滤掉
        assertEquals(listOf("t"), host3.pressDigits("8").map { it.pinYin })
        val host4 = BehaviorHost(FakeJob())
        assertEquals(listOf("an", "ao", "bo", "a", "b", "c"), host4.pressDigits("26").map { it.pinYin })
        val host5 = BehaviorHost(FakeJob())
        // 23：两字母拼音只有 ce（i 在 4 键不在 3 键；ra 不是合法音节）
        assertEquals(listOf("ce", "a", "b", "c"), host5.pressDigits("23").map { it.pinYin })
    }

    @Test
    fun multiLetterPinRewritesInput() {
        val host = BehaviorHost(FakeJob())
        host.pressDigits("726")
        assertEquals("pan'", host.select("pan"))
    }

    @Test
    fun prefixPinLeavesTailRaw() {
        val host = BehaviorHost(FakeJob())
        host.pressDigits("726")
        // "pa" 只钉前两码，尾段 "6" 保持原始数字（与未选尾段同口径）
        assertEquals("pa'6", host.select("pa"))
    }

    @Test
    fun pinAfterPinKeepsFirstPin() {
        val host = BehaviorHost(FakeJob())
        host.pressDigits("726")
        assertEquals("pan'", host.select("pan"))
        host.flowed(InputString("2"))
        assertEquals("pan'2", host.build())
        assertEquals("pan'a'", host.select("a"))
    }

    @Test
    fun pinsChainAcrossSegments() {
        val host = BehaviorHost(FakeJob())
        // "54"→键位码 JG→ji/li；"45" 是 GJ→g/h，不是 ji
        host.pressDigits("54")
        assertEquals("ji'", host.select("ji"))
        host.flowed(InputString("6"))
        assertEquals("ji'6", host.build())
        assertEquals("ji'm'", host.select("m"))
    }

    @Test
    fun unselectedTailKeepsRawDigits() {
        val host = BehaviorHost(FakeJob())
        host.pressDigits("4")
        assertEquals("g'", host.select("g"))
        host.flowed(InputString("5"))
        assertEquals("g'5", host.build())
    }
}
