package com.jobeen.ime

import com.jobeen.ime.engine.rime.behavior.InputString
import com.jobeen.ime.engine.rime.behavior.SelectPinYin
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.host.BehaviorHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 左侧拼音列点选（SelectPinYin）的组字拼接契约。
 *
 * 复演真机操作序列：按数字键 -> 经 possiblePinYin 拿到当前段的可选
 * 拼音 -> 点选其中一个 -> 再按键……build() 必须把每段已选拼音替换
 * 进原始输入并加分隔符，不得残留原始数字（真机故障形态为
 * 「g'4j'5…」每段多一个数字、选词上屏出错）。
 *
 * 大小写契约：回写一律用左列拼音的原始小写形态。引擎侧认这个
 * 形态的前提是方案 speller algebra 启用单字母缩写拼写（abbrev）——
 * 万象 18.1.6 的 T9 方案曾把该规则注释掉致点选失效，Jime 内置包
 * 以第四处适配恢复之（见打包铁律）。左列显示与回写同源同值。
 */
class BehaviorHostSelectPinYinTest {

    private class FakeJob : IRimeJob {
        override fun sendJob(block: suspend RimeApi.() -> Unit) { /* 只登记队列，不投递引擎 */ }
        override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T =
            defaultValue
    }

    /** 按一个数字键并点选指定拼音，返回点选后的 build。 */
    private fun BehaviorHost.pressAndSelect(
        type: String, digit: String, letter: String
    ): String {
        flowed(InputString(digit))
        val options = possiblePinYin(type, build(), 0)
        val pick = options.firstOrNull { it.pinYin == letter }
        assertNotNull("possible should contain '$letter' for digit $digit", pick)
        flowed(SelectPinYin(pick!!))
        return build()
    }

    @Test
    fun t9SelectionsRewriteLowercaseWithoutRawDigits() {
        val host = BehaviorHost(FakeJob())
        var built = host.pressAndSelect("T9PinYin", "4", "g")
        assertEquals("g'", built)
        built = host.pressAndSelect("T9PinYin", "5", "j")
        assertEquals("g'j'", built)
        built = host.pressAndSelect("T9PinYin", "6", "m")
        assertEquals("g'j'm'", built)
        built = host.pressAndSelect("T9PinYin", "7", "p")
        assertEquals("g'j'm'p'", built)
        built = host.pressAndSelect("T9PinYin", "7", "q")
        assertEquals("g'j'm'p'q'", built)
    }

    @Test
    fun fullPinyinSelectionsStayLowercase() {
        val host = BehaviorHost(FakeJob())
        host.flowed(InputString("z"))
        val options = host.possiblePinYin("PinYin", host.build(), 0)
        val pick = options.firstOrNull { it.pinYin == "z" }
        assertNotNull(pick)
        host.flowed(SelectPinYin(pick!!))
        assertEquals("z'", host.build())
    }

    @Test
    fun unselectedTailKeepsRawDigits() {
        val host = BehaviorHost(FakeJob())
        host.pressAndSelect("T9PinYin", "4", "g")
        host.flowed(InputString("5"))
        // 第二段未点选：保持原始数字，等用户点选或直接选词
        assertEquals("g'5", host.build())
    }
}
