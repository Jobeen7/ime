package com.jobeen.ime

import com.jobeen.ime.base.speech.FinalTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class FinalTextNormalizerTest {

    // ── 间距规整（原 normalizeCjkSpacing 行为保持不变） ──

    @Test
    fun spacing_removesSpaceBetweenCjk() {
        assertEquals("你好世界", FinalTextNormalizer.normalizeSpacing("你好 世界"))
    }

    @Test
    fun spacing_keepsSpaceBetweenAsciiWords() {
        assertEquals("hello world", FinalTextNormalizer.normalizeSpacing("hello world"))
    }

    @Test
    fun spacing_removesSpaceBeforeAsciiPunctuation() {
        assertEquals("hello, world", FinalTextNormalizer.normalizeSpacing("hello , world"))
    }

    @Test
    fun spacing_trimsAndHandlesEmpty() {
        assertEquals("", FinalTextNormalizer.normalizeSpacing("   "))
        assertEquals("你好", FinalTextNormalizer.normalizeSpacing("  你好  "))
    }

    // ── 标点合并 ──

    @Test
    fun final_mergesRepeatedSentencePeriods() {
        assertEquals("好的。", FinalTextNormalizer.normalizeFinal("好的。。"))
        assertEquals("好的。", FinalTextNormalizer.normalizeFinal("好的。。。"))
    }

    @Test
    fun final_mergesRepeatedCommas() {
        assertEquals("我，我来了。", FinalTextNormalizer.normalizeFinal("我，，我来了"))
    }

    @Test
    fun final_keepsEmphasisAndEllipsisUntouched() {
        // 「！」不在合并集合：感叹强调不被吞
        assertEquals("太好了！！", FinalTextNormalizer.normalizeFinal("太好了！！"))
        // 省略号原样保留；结尾是「…」不补句号
        assertEquals("我想想……", FinalTextNormalizer.normalizeFinal("我想想……"))
    }

    // ── 句末补句号 ──

    @Test
    fun final_appendsPeriodForCjkEnding() {
        assertEquals("我们明天见。", FinalTextNormalizer.normalizeFinal("我们明天见"))
    }

    @Test
    fun final_noAppendWhenAlreadyTerminated() {
        assertEquals("我们明天见。", FinalTextNormalizer.normalizeFinal("我们明天见。"))
        assertEquals("真的吗？", FinalTextNormalizer.normalizeFinal("真的吗？"))
        assertEquals("太好了！", FinalTextNormalizer.normalizeFinal("太好了！"))
    }

    @Test
    fun final_noAppendForAsciiOrDigitEnding() {
        assertEquals("see you tomorrow", FinalTextNormalizer.normalizeFinal("see you tomorrow"))
        assertEquals("一共 123", FinalTextNormalizer.normalizeFinal("一共 123"))
        assertEquals("版本号 v2", FinalTextNormalizer.normalizeFinal("版本号 v2"))
    }

    @Test
    fun final_noAppendForShortOrEmpty() {
        assertEquals("", FinalTextNormalizer.normalizeFinal(""))
        assertEquals("好", FinalTextNormalizer.normalizeFinal("好"))
    }

    // ── 第二遍补标点：分句逗号 ──

    @Test
    fun final_addsCommaBeforeConnectiveAfterLongRun() {
        assertEquals("我今天去买菜，然后回家做饭。", FinalTextNormalizer.normalizeFinal("我今天去买菜然后回家做饭"))
        assertEquals("会议改到明天，因为今天下雨了。", FinalTextNormalizer.normalizeFinal("会议改到明天因为今天下雨了"))
        assertEquals("他今天下午没来，但是他说明天一定到。", FinalTextNormalizer.normalizeFinal("他今天下午没来但是他说明天一定到"))
    }

    @Test
    fun final_noCommaForShortRunOrAtStart() {
        // 短分句不补逗号
        assertEquals("你要茶还是咖啡。", FinalTextNormalizer.normalizeFinal("你要茶还是咖啡"))
        assertEquals("我喜欢水果比如苹果。", FinalTextNormalizer.normalizeFinal("我喜欢水果比如苹果"))
        // 连接词在文首不补
        assertEquals("然后我们就回家了。", FinalTextNormalizer.normalizeFinal("然后我们就回家了"))
        // 已有标点后重新起算，短段不补
        assertEquals("下雨了，我们回家然后吃饭。", FinalTextNormalizer.normalizeFinal("下雨了，我们回家然后吃饭"))
        // 已有标点后长段仍补
        assertEquals("今天天气很好，我们去公园玩，然后回家。", FinalTextNormalizer.normalizeFinal("今天天气很好，我们去公园玩然后回家"))
    }

    @Test
    fun final_noCommaInsideFixedPhrases() {
        // 之所以、是因为不拆
        assertEquals("这件事情之所以失败是因为准备不足。", FinalTextNormalizer.normalizeFinal("这件事情之所以失败是因为准备不足"))
        assertEquals("他没来是因为生病了。", FinalTextNormalizer.normalizeFinal("他没来是因为生病了"))
    }

    // ── 第二遍补标点：句中「吧」后补句号 ──

    @Test
    fun final_addsPeriodAfterBaWithLongContinuation() {
        assertEquals("你先走吧。我等会就来。", FinalTextNormalizer.normalizeFinal("你先走吧我等会就来"))
        assertEquals("好吧。那我们就出发了。", FinalTextNormalizer.normalizeFinal("好吧那我们就出发了"))
        assertEquals("今天天气很好我们去公园玩吧。明天再去游泳。", FinalTextNormalizer.normalizeFinal("今天天气很好我们去公园玩吧明天再去游泳"))
    }

    @Test
    fun final_noPeriodAfterBaForBarOrShortContinuation() {
        // 酒吧、吧台不补
        assertEquals("我们去酒吧喝一杯。", FinalTextNormalizer.normalizeFinal("我们去酒吧喝一杯"))
        assertEquals("吧台边坐着一个人。", FinalTextNormalizer.normalizeFinal("吧台边坐着一个人"))
        // 吧后太短不拆
        assertEquals("好吧我们走。", FinalTextNormalizer.normalizeFinal("好吧我们走"))
        // 文末的吧由文末收尾处理
        assertEquals("我们去公园玩吧。", FinalTextNormalizer.normalizeFinal("我们去公园玩吧"))
    }

    // ── 文末问句 ──

    @Test
    fun final_appendsQuestionMarkForMaEnding() {
        assertEquals("你吃饭了吗？", FinalTextNormalizer.normalizeFinal("你吃饭了吗"))
        assertEquals("是这么回事么？", FinalTextNormalizer.normalizeFinal("是这么回事么"))
    }

    @Test
    fun final_keepsPeriodForNeEnding() {
        // 「呢」兼疑问与语气助词，保守起见仍补句号（已知局限）
        assertEquals("你呢。", FinalTextNormalizer.normalizeFinal("你呢"))
    }

    // ── 已有标点原样通过＋幂等 ──

    @Test
    fun final_keepsExistingPunctuationUntouched() {
        assertEquals("今天天气很好，我们去公园玩吧。", FinalTextNormalizer.normalizeFinal("今天天气很好，我们去公园玩吧。"))
        assertEquals("你吃饭了吗？我吃了。", FinalTextNormalizer.normalizeFinal("你吃饭了吗？我吃了"))
    }

    @Test
    fun final_isIdempotent() {
        val cases = listOf(
            "我今天去买菜然后回家做饭",
            "你先走吧我等会就来",
            "你吃饭了吗",
            "今天天气很好我们去公园玩吧明天再去游泳",
        )
        for (input in cases) {
            val once = FinalTextNormalizer.normalizeFinal(input)
            assertEquals(once, FinalTextNormalizer.normalizeFinal(once))
        }
    }
}
