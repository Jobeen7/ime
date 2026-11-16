package com.jobeen.ime

import com.jobeen.ime.engine.rime.behavior.InputString
import com.jobeen.ime.engine.rime.behavior.SelectPinYin
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.host.BehaviorHost
import com.jobeen.ime.skin.layout.CompactLayoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紧凑键盘（14/18 键）左侧拼音列：与 T9 侧栏同构，只换展开函数。
 *
 * T14/T18 键按首字母进组字（compactAlphabetKey）：按“qw”组进 "q"、
 * 按“op”组进 "o"，队列里是首字母串 "qo"；possibleCompactPinYin
 * 按当前分组（group.code＝组首字母大写）展开——"wo" 的码正是
 * "QO"（w∈qw 组、o∈op 组）。native t9PinyinList 是 9 键分组，
 * 不参与此路（引擎侧 compactGrouping 非空时 t9ListRaw 为 null）。
 */
class BehaviorHostCompactPinYinTest {

    private class FakeJob : IRimeJob {
        override fun sendJob(block: suspend RimeApi.() -> Unit) { /* 只登记队列，不投递引擎 */ }
        override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T =
            defaultValue
    }

    @Test
    fun t14CompactPinYinExpandsWo() {
        val grouping = CompactLayoutGenerator.groupingFor(14)
        assertNotNull(grouping)
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("q"))
        host.flowed(InputString("o"))
        val options = host.possibleCompactPinYin(grouping!!, host.build(), 0)
        val wo = options.firstOrNull { it.pinYin == "wo" }
        assertNotNull("compact T14 should contain 'wo' for keys qw+op", wo)
        assertEquals("qo", wo!!.raw)
        assertEquals(0, wo.position)
    }

    @Test
    fun t18CompactPinYinExpandsWo() {
        val grouping = CompactLayoutGenerator.groupingFor(18)
        assertNotNull(grouping)
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("q"))
        host.flowed(InputString("o"))
        val options = host.possibleCompactPinYin(grouping!!, host.build(), 0)
        val wo = options.firstOrNull { it.pinYin == "wo" }
        assertNotNull("compact T18 should contain 'wo' for keys qw+o", wo)
        assertEquals("qo", wo!!.raw)
        assertEquals(0, wo.position)
    }

    @Test
    fun compactPinYinSelectRewritesRawLetters() {
        val grouping = CompactLayoutGenerator.groupingFor(14)
        assertNotNull(grouping)
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("q"))
        host.flowed(InputString("o"))
        val options = host.possibleCompactPinYin(grouping!!, host.build(), 0)
        val wo = options.firstOrNull { it.pinYin == "wo" }
        assertNotNull(wo)
        host.flowed(SelectPinYin(wo!!))
        assertEquals("wo'", host.build())
    }

    @Test
    fun compactPinYinEmptyWhenNoInput() {
        val grouping = CompactLayoutGenerator.groupingFor(14)
        assertNotNull(grouping)
        val host = BehaviorHost(FakeJob())
        assertTrue(host.possibleCompactPinYin(grouping!!, host.build(), 0).isEmpty())
    }
}
