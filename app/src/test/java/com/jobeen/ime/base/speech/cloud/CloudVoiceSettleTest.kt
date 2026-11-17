package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文本收尾机：预览／提交／结算／丢弃口径。 */
class CloudVoiceSettleTest {

    private class RecordingSink : CloudTextSink {
        val calls = mutableListOf<String>()
        override fun setPreview(text: String) {
            calls.add("preview:$text")
        }

        override fun commit(text: String) {
            calls.add("commit:$text")
        }

        override fun clearPreview() {
            calls.add("clear")
        }
    }

    @Test
    fun settle_partialPreviews_finalCommits_endCommitsNothingMore() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("你好")
        assertTrue(settle.hasPartial)
        settle.onFinal("你好世界")
        assertFalse(settle.hasPartial)
        settle.onEnded()
        assertEquals(listOf("preview:你好", "commit:你好世界"), sink.calls)
    }

    @Test
    fun settle_endWithoutFinal_commitsLatestPartial() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("第一段")
        settle.onPartial("第一段第二段")
        settle.onEnded()
        assertEquals(
            listOf("preview:第一段", "preview:第一段第二段", "commit:第一段第二段"),
            sink.calls,
        )
    }

    @Test
    fun settle_stopDiscard_clears_stopKeep_commitsPartial() {
        val sink = RecordingSink()
        val discarded = CloudVoiceSettle(sink)
        discarded.onPartial("不要了")
        discarded.stop(discard = true)
        assertEquals(listOf("preview:不要了", "clear"), sink.calls)

        val kept = CloudVoiceSettle(sink)
        kept.onPartial("留着")
        kept.stop(discard = false)
        assertEquals(
            listOf("preview:不要了", "clear", "preview:留着", "commit:留着"),
            sink.calls,
        )
    }

    @Test
    fun settle_error_clears_andDropsLaterResults() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("半句")
        settle.onError()
        settle.onPartial("迟到")
        settle.onFinal("迟到终态")
        settle.onEnded()
        assertEquals(listOf("preview:半句", "clear"), sink.calls)
    }

    @Test
    fun settle_emptyFinalIgnored_emptyPartialEndCommitsNothing() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onFinal("")
        settle.onPartial("")
        settle.onEnded()
        assertEquals(listOf("preview:"), sink.calls)
    }

    @Test
    fun settle_displayTransformApplied() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink) { it.uppercase() }
        settle.onPartial("abc")
        settle.onEnded()
        assertEquals(listOf("preview:ABC", "commit:ABC"), sink.calls)
    }

    @Test
    fun settle_stopAfterEnded_isNoop() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("一句")
        settle.onEnded()
        settle.stop(discard = true)
        assertEquals(listOf("preview:一句", "commit:一句"), sink.calls)
    }

    // 按句记账：连续两句 final 均落屏（NLS 每句 SentenceEnd 发一次
    // final、腾讯每片 slice_type=2 发一次 final）。
    @Test
    fun settle_twoFinalsInARow_bothCommit() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("第一句")
        settle.onFinal("第一句。")
        settle.onPartial("第二句")
        settle.onFinal("第二句。")
        settle.onEnded()
        assertEquals(
            listOf("preview:第一句", "commit:第一句。", "preview:第二句", "commit:第二句。"),
            sink.calls,
        )
    }

    @Test
    fun settle_finalThenNewPartial_endCommitsTailPartial() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("第一句")
        settle.onFinal("第一句。")
        settle.onPartial("第二句没说完")
        settle.onEnded()
        assertEquals(
            listOf("preview:第一句", "commit:第一句。", "preview:第二句没说完", "commit:第二句没说完"),
            sink.calls,
        )
    }

    // B-S1：final 即收尾，迟到的 partial 不再覆写已提交文本，
    // onEnded 也不再把陈旧 partial 二次提交。
    @Test
    fun settle_finalSettles_latePartialDropped_noDoubleCommit() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("你好")
        settle.onFinal("你好世界")
        settle.onPartial("你好世界旧尾巴")
        settle.onEnded()
        settle.onFinal("再来一次终态")
        assertEquals(listOf("preview:你好", "commit:你好世界"), sink.calls)
        assertFalse(settle.hasPartial)
    }

    // 1.10：final 之后直接到达的 final 同 onPartial 的新句检测——
    // 与已提交句互为前缀是同一句迟到重发（丢弃），否则是新句的 final
    // （NLS 每句 SentenceEnd、腾讯每片发一次），开新句落屏。旧口径
    // settled 即返，中间无 partial 的第二句直接丢掉。
    @Test
    fun settle_secondFinalWithoutPartial_newSentenceCommits() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("第一句")
        settle.onFinal("第一句。")
        // 第二句无 partial、直接 final：必须落屏，不能丢。
        settle.onFinal("第二句。")
        settle.onEnded()
        assertEquals(
            listOf("preview:第一句", "commit:第一句。", "commit:第二句。"),
            sink.calls,
        )
    }

    @Test
    fun settle_secondFinalSameSentencePrefix_dropped() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onPartial("你好")
        settle.onFinal("你好世界")
        // 同一句的迟到重发（与已提交句互为前缀）：丢弃，不二次提交。
        settle.onFinal("你好世界！")
        settle.onFinal("你好")
        settle.onEnded()
        assertEquals(listOf("preview:你好", "commit:你好世界"), sink.calls)
    }

    // 空 final 不收尾（旧口径）：其后的 partial 仍有效，onEnded 照常结算。
    @Test
    fun settle_emptyFinalDoesNotSettle() {
        val sink = RecordingSink()
        val settle = CloudVoiceSettle(sink)
        settle.onFinal("")
        settle.onPartial("随后")
        settle.onEnded()
        assertEquals(listOf("preview:随后", "commit:随后"), sink.calls)
    }
}
