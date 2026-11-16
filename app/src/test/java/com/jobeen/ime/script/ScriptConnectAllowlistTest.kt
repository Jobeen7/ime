// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本联网白名单＋代理策略单测（纯函数，不碰 Android 框架与网络）。 */
class ScriptConnectAllowlistTest {

    // --- parse ---

    @Test
    fun parse_noHeader_returnsEmpty() {
        assertTrue(ScriptConnectAllowlist.parse("async function output() { return []; }").isEmpty())
        assertTrue(ScriptConnectAllowlist.parse("").isEmpty())
    }

    @Test
    fun parse_singleAndMultipleHosts() {
        val parsed = ScriptConnectAllowlist.parse(
            "// @connect api.example.com\n// @connect cdn.example.org\nasync function output() { return []; }",
        )
        assertEquals(setOf("api.example.com", "cdn.example.org"), parsed)
    }

    @Test
    fun parse_directiveCaseInsensitive_hostLowercased() {
        val parsed = ScriptConnectAllowlist.parse("// @CONNECT API.Example.COM\nasync function output() { return []; }")
        assertEquals(setOf("api.example.com"), parsed)
    }

    @Test
    fun parse_wildcardKept_trailingDotAndPortStripped() {
        val parsed = ScriptConnectAllowlist.parse(
            "// @connect *.example.com\n// @connect api.example.net.\n// @connect host.example.io:8443\nasync function output() { return []; }",
        )
        assertEquals(setOf("*.example.com", "api.example.net", "host.example.io"), parsed)
    }

    @Test
    fun parse_stopsAtFirstCodeLine() {
        val parsed = ScriptConnectAllowlist.parse(
            "// @connect first.example.com\nvar x = 1;\n// @connect second.example.com\nasync function output() { return []; }",
        )
        assertEquals(setOf("first.example.com"), parsed)
    }

    @Test
    fun parse_blankLinesAndNonConnectCommentsSkipped() {
        val parsed = ScriptConnectAllowlist.parse(
            "\n// 这是一段注释\n// @connect api.example.com\nasync function output() { return []; }",
        )
        assertEquals(setOf("api.example.com"), parsed)
    }

    @Test
    fun parse_bareStarIgnored_duplicatesDeduped() {
        val parsed = ScriptConnectAllowlist.parse(
            "// @connect *\n// @connect api.example.com\n// @connect API.EXAMPLE.COM\nasync function output() { return []; }",
        )
        assertEquals(setOf("api.example.com"), parsed)
    }

    // --- isHostAllowed ---

    @Test
    fun isHostAllowed_exactMatch_caseInsensitive() {
        val allowlist = setOf("api.example.com")
        assertTrue(ScriptConnectAllowlist.isHostAllowed("api.example.com", allowlist))
        assertTrue(ScriptConnectAllowlist.isHostAllowed("API.EXAMPLE.COM", allowlist))
        assertFalse(ScriptConnectAllowlist.isHostAllowed("other.example.com", allowlist))
    }

    @Test
    fun isHostAllowed_wildcardMatchesSubdomainsNotApex() {
        val allowlist = setOf("*.example.com")
        assertTrue(ScriptConnectAllowlist.isHostAllowed("a.example.com", allowlist))
        assertTrue(ScriptConnectAllowlist.isHostAllowed("a.b.example.com", allowlist))
        assertFalse(ScriptConnectAllowlist.isHostAllowed("example.com", allowlist))
        assertFalse(ScriptConnectAllowlist.isHostAllowed("badexample.com", allowlist))
        assertFalse(ScriptConnectAllowlist.isHostAllowed("a.example.org", allowlist))
    }

    @Test
    fun isHostAllowed_emptyAllowlistDeniesAll_nullHostDenied() {
        assertFalse(ScriptConnectAllowlist.isHostAllowed("api.example.com", emptySet()))
        assertFalse(ScriptConnectAllowlist.isHostAllowed(null, setOf("api.example.com")))
        assertFalse(ScriptConnectAllowlist.isHostAllowed("", setOf("api.example.com")))
    }

    // --- proxy policy ---

    @Test
    fun proxy_allowedWhenDeclared() {
        val allowlist = setOf("api.example.com", "*.cdn.example.org")
        assertEquals(
            ProxyDecision.Allowed,
            ScriptHttpProxyPolicy.check("https://api.example.com/x?q=1", allowlist),
        )
        assertEquals(
            ProxyDecision.Allowed,
            ScriptHttpProxyPolicy.check("http://a.cdn.example.org/f", allowlist),
        )
    }

    @Test
    fun proxy_deniedWhenUndeclared() {
        val decision = ScriptHttpProxyPolicy.check(
            "https://evil.example.com/x",
            setOf("api.example.com"),
        )
        assertTrue(decision is ProxyDecision.Denied)
        assertEquals(
            "host not declared in @connect: evil.example.com",
            (decision as ProxyDecision.Denied).reason,
        )
    }

    @Test
    fun proxy_deniedWhenAllowlistEmpty_subdomainOfApexDenied() {
        assertTrue(
            ScriptHttpProxyPolicy.check("https://api.example.com/", emptySet())
                is ProxyDecision.Denied,
        )
        assertTrue(
            ScriptHttpProxyPolicy.check(
                "https://api.example.com/",
                setOf("example.com"),
            ) is ProxyDecision.Denied,
        )
    }

    @Test
    fun proxy_nonHttpSchemeDenied_invalidUrlDenied() {
        assertTrue(
            ScriptHttpProxyPolicy.check("ftp://api.example.com/", setOf("api.example.com"))
                is ProxyDecision.Denied,
        )
        assertTrue(
            ScriptHttpProxyPolicy.check("::bad", setOf("api.example.com"))
                is ProxyDecision.Denied,
        )
    }

    @Test
    fun proxy_localhostOnlyWhenDeclared_portIgnored() {
        assertTrue(
            ScriptHttpProxyPolicy.check("http://127.0.0.1:8080/x", setOf("127.0.0.1"))
                is ProxyDecision.Allowed,
        )
        assertTrue(
            ScriptHttpProxyPolicy.check("http://127.0.0.1:8080/x", setOf("localhost"))
                is ProxyDecision.Denied,
        )
    }

    @Test
    fun proxy_uppercaseHostAllowed() {
        assertEquals(
            ProxyDecision.Allowed,
            ScriptHttpProxyPolicy.check("https://API.EXAMPLE.COM/x", setOf("api.example.com")),
        )
    }
}
