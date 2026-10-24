// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemeDownload
import com.jobeen.ime.data.manager.SchemeDownload.DownloadEntry
import com.jobeen.ime.data.manager.SchemeDownload.DownloadErrorKind
import com.jobeen.ime.data.manager.SchemeDownload.HeadersResult
import com.jobeen.ime.data.manager.SchemeDownload.UrlInvalidReason
import com.jobeen.ime.data.manager.SchemeDownload.UrlValidation
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.SchemePackageImport.PackageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第三期远程下载纯逻辑单测：URL 校验、Header 组装、条目编解码、登记来源。 */
class SchemeDownloadTest {

    // ---- URL 校验：仅限 HTTPS ----

    @Test
    fun validHttpsUrlAccepted() {
        val result = SchemeDownload.validateDownloadUrl(
            "https://github.com/amzxyz/rime-wanxiang/releases/download/v18.1.7/rime-wanxiang-pure.zip",
        )
        assertTrue(result is UrlValidation.Valid)
    }

    @Test
    fun urlTrimmedBeforeValidation() {
        val result = SchemeDownload.validateDownloadUrl("  https://example.com/pkg.zip\n")
        assertEquals(
            UrlValidation.Valid("https://example.com/pkg.zip"),
            result,
        )
    }

    @Test
    fun uppercaseHttpsSchemeAccepted() {
        assertTrue(
            SchemeDownload.validateDownloadUrl("HTTPS://example.com/pkg.zip")
                is UrlValidation.Valid,
        )
    }

    @Test
    fun httpUrlRejected() {
        assertEquals(
            UrlValidation.Invalid(UrlInvalidReason.NOT_HTTPS),
            SchemeDownload.validateDownloadUrl("http://example.com/pkg.zip"),
        )
    }

    @Test
    fun nonHttpSchemeRejected() {
        assertEquals(
            UrlValidation.Invalid(UrlInvalidReason.NOT_HTTPS),
            SchemeDownload.validateDownloadUrl("ftp://example.com/pkg.zip"),
        )
    }

    @Test
    fun schemeLessUrlRejectedAsNotHttps() {
        assertEquals(
            UrlValidation.Invalid(UrlInvalidReason.NOT_HTTPS),
            SchemeDownload.validateDownloadUrl("example.com/pkg.zip"),
        )
    }

    @Test
    fun emptyUrlRejected() {
        assertEquals(
            UrlValidation.Invalid(UrlInvalidReason.EMPTY),
            SchemeDownload.validateDownloadUrl("   "),
        )
    }

    @Test
    fun malformedUrlRejected() {
        assertEquals(
            UrlValidation.Invalid(UrlInvalidReason.MALFORMED),
            SchemeDownload.validateDownloadUrl("https://exa mple.com/%zz"),
        )
    }

    @Test
    fun httpsWithoutHostRejected() {
        assertEquals(
            UrlValidation.Invalid(UrlInvalidReason.NO_HOST),
            SchemeDownload.validateDownloadUrl("https:///pkg.zip"),
        )
    }

    // ---- Header 组装：不持久化，只在本次下载使用 ----

    @Test
    fun validHeadersAssembled() {
        val result = SchemeDownload.buildHeaders(
            listOf("Authorization" to "Bearer token", "X-Custom" to "v1"),
        )
        assertEquals(
            HeadersResult.Ok(
                listOf("Authorization" to "Bearer token", "X-Custom" to "v1"),
            ),
            result,
        )
    }

    @Test
    fun emptyHeaderRowsDropped() {
        val result = SchemeDownload.buildHeaders(
            listOf("" to "", "Accept" to "application/zip"),
        )
        assertEquals(
            HeadersResult.Ok(listOf("Accept" to "application/zip")),
            result,
        )
    }

    @Test
    fun blankNameWithValueRejectedWithRowIndex() {
        val result = SchemeDownload.buildHeaders(
            listOf("Accept" to "application/zip", "" to "oops"),
        )
        assertEquals(HeadersResult.InvalidRow(1), result)
    }

    @Test
    fun headerNameWithSpaceOrColonRejected() {
        assertEquals(
            HeadersResult.InvalidRow(0),
            SchemeDownload.buildHeaders(listOf("Bad Name" to "v")),
        )
        assertEquals(
            HeadersResult.InvalidRow(0),
            SchemeDownload.buildHeaders(listOf("Bad:Name" to "v")),
        )
    }

    @Test
    fun headerValueWithCrlfRejected() {
        assertEquals(
            HeadersResult.InvalidRow(0),
            SchemeDownload.buildHeaders(listOf("X-A" to "line1\nline2")),
        )
        assertEquals(
            HeadersResult.InvalidRow(0),
            SchemeDownload.buildHeaders(listOf("X-A" to "line1\rline2")),
        )
    }

    // ---- 下载条目编解码：只存名 + URL ----

    @Test
    fun entryCodecRoundTrip() {
        val entries = listOf(
            DownloadEntry("雾凇拼音", "https://example.com/rime-ice.zip"),
            DownloadEntry("万象 Pure", "https://example.com/pure.zip"),
        )
        val decoded = SchemeDownload.decodeDownloadEntries(
            SchemeDownload.encodeDownloadEntries(entries),
        )
        assertEquals(entries, decoded)
    }

    @Test
    fun entryCodecSanitizesNameAndDropsDirtyLines() {
        val encoded = "bad\t\n" +
            "only-name\n" +
            "雾凇\t https://example.com/ice.zip \n" +
            "\n"
        val decoded = SchemeDownload.decodeDownloadEntries(encoded)
        assertEquals(
            listOf(DownloadEntry("雾凇", "https://example.com/ice.zip")),
            decoded,
        )
    }

    @Test
    fun entryNameTabNewlineSanitized() {
        assertEquals(
            "a_b_c",
            SchemeDownload.sanitizeEntryName("a\tb\nc"),
        )
    }

    @Test
    fun upsertReplacesSameNameEntry() {
        val entries = listOf(DownloadEntry("雾凇", "https://old.example.com/a.zip"))
        val updated = SchemeDownload.upsertEntry(
            entries,
            DownloadEntry("雾凇", "https://new.example.com/b.zip"),
        )
        assertEquals(
            listOf(DownloadEntry("雾凇", "https://new.example.com/b.zip")),
            updated,
        )
    }

    @Test
    fun decodeNeverDropsSavedEntryForBadUrl() {
        // URL 合法性只在下载时校验：解码不因 URL 格式丢条目。
        val decoded = SchemeDownload.decodeDownloadEntries(
            "旧条目\thttp://example.com/old.zip",
        )
        assertEquals(
            listOf(DownloadEntry("旧条目", "http://example.com/old.zip")),
            decoded,
        )
    }

    // ---- 登记来源：URL 与本地导入区分，向后兼容二期旧行 ----

    @Test
    fun registrySourceRoundTrip() {
        val registry = SchemePackageImport.SchemePackageRegistry(
            listOf(
                SchemePackageImport.SchemePackageRecord(
                    "local-pkg", "1.0", listOf("a"), listOf("a.schema.yaml"),
                    PackageSource.LOCAL,
                ),
                SchemePackageImport.SchemePackageRecord(
                    "url-pkg", "2.0", listOf("b"), listOf("b.schema.yaml"),
                    PackageSource.URL,
                ),
            ),
        )
        val decoded = SchemePackageImport.decodeRegistry(
            SchemePackageImport.encodeRegistry(registry),
        )
        assertEquals(PackageSource.LOCAL, decoded.sourceForScheme("a"))
        assertEquals(PackageSource.URL, decoded.sourceForScheme("b"))
        assertEquals(null, decoded.sourceForScheme("missing"))
    }

    @Test
    fun legacyFourFieldRegistryLineDecodesAsLocal() {
        val decoded = SchemePackageImport.decodeRegistry(
            "old-pkg\t1.0\ta\t" + "a.schema.yaml",
        )
        assertEquals(PackageSource.LOCAL, decoded.sourceForScheme("a"))
        assertEquals("old-pkg", decoded.packageForScheme("a"))
    }

    // ---- 下载 HTTP 状态映射 ----

    @Test
    fun redirectStatusMapsToDowngradeRefusal() {
        assertEquals(DownloadErrorKind.REDIRECT_DOWNGRADE, SchemeDownload.errorKindForHttp(301))
        assertEquals(DownloadErrorKind.REDIRECT_DOWNGRADE, SchemeDownload.errorKindForHttp(308))
        assertEquals(DownloadErrorKind.HTTP_STATUS, SchemeDownload.errorKindForHttp(404))
        assertEquals(DownloadErrorKind.HTTP_STATUS, SchemeDownload.errorKindForHttp(500))
    }
}
