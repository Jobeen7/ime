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
}
