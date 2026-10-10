package com.jobeen.ime

import com.jobeen.ime.base.speech.parseGithubReleaseAssetUrl
import com.jobeen.ime.base.speech.tofuExpectedSha
import com.jobeen.ime.engine.rime.data.userdict.decodeHrefImpl
import com.jobeen.ime.engine.rime.data.userdict.unescapeXmlEntities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 安全审查修复批次（语音模型摘要校验、WebDAV href 解码）的纯函数测试。
 */
class SecurityFixesTest {

    // ---- 语音模型：GitHub 资产链接解析 ----

    @Test
    fun githubAssetUrl_direct() {
        val ref = parseGithubReleaseAssetUrl(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/model.tar.bz2",
        )
        assertEquals("k2-fsa", ref?.owner)
        assertEquals("sherpa-onnx", ref?.repo)
        assertEquals("asr-models", ref?.tag)
        assertEquals("model.tar.bz2", ref?.assetName)
    }

    @Test
    fun githubAssetUrl_behindProxyPrefix() {
        val ref = parseGithubReleaseAssetUrl(
            "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/" +
                "download/asr-models-qnn-binary/qnn-model.tar.bz2",
        )
        assertEquals("k2-fsa", ref?.owner)
        assertEquals("sherpa-onnx", ref?.repo)
        assertEquals("asr-models-qnn-binary", ref?.tag)
        assertEquals("qnn-model.tar.bz2", ref?.assetName)
    }

    @Test
    fun githubAssetUrl_nonGithubOrMalformed() {
        assertNull(parseGithubReleaseAssetUrl("https://example.com/models/model.tar.bz2"))
        assertNull(parseGithubReleaseAssetUrl("https://github.com/k2-fsa/sherpa-onnx"))
        assertNull(
            parseGithubReleaseAssetUrl(
                "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
            ),
        )
    }

    @Test
    fun githubAssetUrl_queryStripped() {
        val ref = parseGithubReleaseAssetUrl(
            "https://github.com/a/b/releases/download/v1/x.bin?token=abc",
        )
        assertEquals("x.bin", ref?.assetName)
    }

    // ---- 语音模型：TOFU 钉住值 ----

    @Test
    fun tofu_sameLinkReturnsPinned() {
        assertEquals(
            "abc123",
            tofuExpectedSha("https://x/m.bin", "abc123", "https://x/m.bin"),
        )
    }

    @Test
    fun tofu_otherLinkOrEmptyReturnsBlank() {
        assertEquals("", tofuExpectedSha("https://x/a.bin", "abc", "https://x/b.bin"))
        assertEquals("", tofuExpectedSha(null, "abc", "https://x/b.bin"))
        assertEquals("", tofuExpectedSha("", "abc", "https://x/b.bin"))
        assertEquals("", tofuExpectedSha("https://x/b.bin", null, "https://x/b.bin"))
    }

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
