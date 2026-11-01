package com.jobeen.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打开链接白名单（审计 E：openURL）：只放行 http/https 有效 host。 */
class OpenUrlPolicyTest {

    @Test
    fun allowsHttpAndHttpsWithHost() {
        assertTrue(OpenUrlPolicy.isAllowed("https://example.com/path?q=1"))
        assertTrue(OpenUrlPolicy.isAllowed("http://example.com"))
        assertTrue(OpenUrlPolicy.isAllowed("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun blocksNonHttpAndHostlessUrls() {
        assertFalse(OpenUrlPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(OpenUrlPolicy.isAllowed("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(OpenUrlPolicy.isAllowed("ftp://example.com/file"))
        assertFalse(OpenUrlPolicy.isAllowed("https:///path"))
        assertFalse(OpenUrlPolicy.isAllowed("https://"))
        assertFalse(OpenUrlPolicy.isAllowed("example.com"))
        assertFalse(OpenUrlPolicy.isAllowed(""))
    }
}
