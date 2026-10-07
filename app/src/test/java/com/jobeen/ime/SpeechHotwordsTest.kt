package com.jobeen.ime

import com.jobeen.ime.base.speech.SpeechHotwords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechHotwordsTest {

    @Test
    fun parseExportReadsWordAndWeight() {
        val entries = SpeechHotwords.parseExport(
            "我们\two men\t120\n吃饭\tchi fan\t45\n缺权重\tque quan zhong\n坏行没有制表符\n只有词\t\n"
        )
        // 无制表符的坏行被跳过；其余 4 行（含拼音列为空的）都收
        assertEquals(4, entries.size)
        assertEquals("我们", entries[0].word)
        assertEquals(120, entries[0].weight)
        assertEquals(45, entries[1].weight)
        // 权重缺失时按 1 计
        assertEquals(1, entries[2].weight)
        assertEquals("只有词", entries[3].word)
        assertEquals(1, entries[3].weight)
    }

    @Test
    fun selectFiltersSortsAndCaps() {
        val entries = listOf(
            SpeechHotwords.DictEntry("吃饭", 10),
            SpeechHotwords.DictEntry("我们", 50),
            SpeechHotwords.DictEntry("吃饭", 99), // 同词取最大权重
            SpeechHotwords.DictEntry("单", 1000), // 单字剔除
            SpeechHotwords.DictEntry("abc软件", 1000), // 含字母剔除
            SpeechHotwords.DictEntry("第1名", 1000), // 含数字剔除
            SpeechHotwords.DictEntry("这是一个超级长的词语啊", 1000), // 超 8 字剔除
            SpeechHotwords.DictEntry("上班", 30)
        )
        val words = SpeechHotwords.selectHotwords(entries)
        assertEquals(listOf("吃饭", "我们", "上班"), words)
    }

    @Test
    fun selectCapsAtMax() {
        val entries = (1..(SpeechHotwords.MAX_HOTWORDS + 50)).map {
            // 生成互不相同的纯汉字词：用序号各位数字映射到不同汉字组合
            val chars = "的一了是我不在人有他这上个们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开"
            val a = chars[it % chars.length]
            val b = chars[(it / chars.length) % chars.length]
            val c = chars[(it * 7) % chars.length]
            SpeechHotwords.DictEntry("$a$b$c", it)
        }
        val words = SpeechHotwords.selectHotwords(entries)
        assertEquals(SpeechHotwords.MAX_HOTWORDS, words.size)
        // 权重最高（序号最大）的一组应排在最前
        assertEquals(entries.last().word, words.first())
    }

    @Test
    fun renderOneWordPerLine() {
        assertEquals("我们\n吃饭\n", SpeechHotwords.render(listOf("我们", "吃饭")))
        assertEquals("", SpeechHotwords.render(emptyList()))
        assertTrue(SpeechHotwords.selectHotwords(emptyList()).isEmpty())
    }
}
