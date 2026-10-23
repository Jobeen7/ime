package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.decodeHrefImpl
import com.jobeen.ime.engine.rime.data.userdict.unescapeXmlEntities
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 安全审查修复批次（WebDAV href 解码）的纯函数测试。
 * （语音模型链接解析用例随 parseGithubReleaseAssetUrl 一并删除：
 * 下载链接只由 ModelDownloader 内部按写死坐标构造、期望摘要直
 * 取目标自带值，不再有链接解析与二次验真。）
 */
class SecurityFixesTest {

    // ---- WebDAV：XML 实体还原 ----

    @Test
    fun xmlEntities_namedAndNumeric() {
        assertEquals("a&b", unescapeXmlEntities("a&amp;b"))
        assertEquals("<tag>", unescapeXmlEntities("&lt;tag&gt;"))
        assertEquals("词", unescapeXmlEntities("&#35789;"))
        assertEquals("\uD83D\uDE00", unescapeXmlEntities("&#x1F600;"))
    }

    @Test
    fun xmlEntities_singlePassAndUnknownKept() {
        // &amp; 只展开一层：&amp;amp; → &amp;（不是 &）
        assertEquals("&amp;", unescapeXmlEntities("&amp;amp;"))
        assertEquals("&nbsp;", unescapeXmlEntities("&nbsp;"))
        assertEquals("plain", unescapeXmlEntities("plain"))
    }

    // ---- WebDAV：href 百分号解码 ----

    @Test
    fun hrefDecode_percentEncodedUtf8() {
        assertEquals("词典.txt", decodeHrefImpl("%E8%AF%8D%E5%85%B8.txt"))
    }

    @Test
    fun hrefDecode_literalAstralCharSurvives() {
        // 星平面字符以原文出现时不得被拆代理对损坏
        val name = "a\uD83D\uDE00b.txt"
        assertEquals(name, decodeHrefImpl(name))
    }

    @Test
    fun hrefDecode_mixedAndBrokenPercent() {
        assertEquals("a b&c", decodeHrefImpl("a%20b&c"))
        assertEquals("100%", decodeHrefImpl("100%"))
        assertEquals("%zz", decodeHrefImpl("%zz"))
    }
}
