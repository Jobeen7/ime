package com.jobeen.ime.base.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WO52 4.2：deep link 路由逐项单测（17 别名＋2 特例＋回落 24 入口＋解析口径）。 */
class DeepLinkRouterTest {

    private fun resolve(uri: String) =
        DeepLinkRouter.resolve(DeepLinkRouter.ACTION_VIEW, uri)

    private fun pageOf(uri: String): String? = resolve(uri)?.pageKey

    // ---- 解析口径 ----

    @Test
    fun `non view action is discarded`() {
        assertNull(DeepLinkRouter.resolve("android.intent.action.SEND", "jime://com.jobeen.ime/rime"))
        assertNull(DeepLinkRouter.resolve(null, "jime://com.jobeen.ime/rime"))
    }

    @Test
    fun `empty data string is discarded`() {
        assertNull(DeepLinkRouter.resolve(DeepLinkRouter.ACTION_VIEW, null))
        assertNull(DeepLinkRouter.resolve(DeepLinkRouter.ACTION_VIEW, ""))
    }

    @Test
    fun `wrong scheme or host is discarded`() {
        assertNull(pageOf("hamster3://com.jobeen.ime/rime"))
        assertNull(pageOf("jime://other.host/rime"))
        assertNull(pageOf("not-a-uri"))
    }

    @Test
    fun `scheme is case-insensitive`() {
        assertEquals("rime", pageOf("JIME://com.jobeen.ime/rime"))
        assertEquals("rime", pageOf("Jime://com.jobeen.ime/rime"))
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals("rime", pageOf("jime://com.jobeen.ime/rime/"))
        assertEquals("rime", pageOf("jime://com.jobeen.ime/rime///"))
    }

    @Test
    fun `empty path is discarded`() {
        assertNull(pageOf("jime://com.jobeen.ime"))
        assertNull(pageOf("jime://com.jobeen.ime/"))
    }

    @Test
    fun `query key is decoded and value kept raw`() {
        val target = resolve("jime://com.jobeen.ime/phrase?id=42&name=a+b&x=%26")!!
        assertEquals("42", target.params["id"])
        // 键里的 + 先换 %2B 再解码：name 的键仍是 name；值取 = 之后原文。
        assertEquals("a+b", target.params["name"])
        assertEquals("%26", target.params["x"])
    }

    @Test
    fun `query key plus stays plus per spec`() {
        // 单子口径：键 URLDecoder.decode 前 + 先换成 %2B，
        // 故键里的 + 解出来仍是 +，不是空格。
        val target = resolve("jime://com.jobeen.ime/phrase?my+key=v")!!
        assertEquals("v", target.params["my+key"])
        assertNull(target.params["my key"])
        // %20 照常解成空格。
        val target2 = resolve("jime://com.jobeen.ime/phrase?my%20key=v")!!
        assertEquals("v", target2.params["my key"])
    }

    @Test
    fun `empty query gives empty params`() {
        assertEquals(emptyMap<String, String>(), resolve("jime://com.jobeen.ime/rime")!!.params)
    }

    @Test
    fun `key without value gives empty value`() {
        assertEquals("", resolve("jime://com.jobeen.ime/rime?flag")!!.params["flag"])
    }

    // ---- 2 特例 ----

    @Test
    fun `keyboard settings with numeric symbols goes to keyboard page`() {
        assertEquals(
            "keyboard",
            pageOf("jime://com.jobeen.ime/keyboardSettings?action=numericSymbols"),
        )
        assertEquals(
            "keyboard",
            pageOf("jime://com.jobeen.ime/keyboardSettings?action=t9Symbols"),
        )
    }

    @Test
    fun `clipboard with phrase action goes to phrase page`() {
        assertEquals(
            "phrase",
            pageOf("jime://com.jobeen.ime/clipboard?action=phrase"),
        )
    }

    @Test
    fun `clipboard without phrase action stays on clipboard page`() {
        assertEquals("clipboard", pageOf("jime://com.jobeen.ime/clipboard"))
        assertEquals(
            "clipboard",
            pageOf("jime://com.jobeen.ime/clipboard?action=clipboard&id=7"),
        )
    }

    // ---- 17 别名 ----

    @Test
    fun `seventeen aliases map to pages`() {
        val expected = mapOf(
            "inputSchema" to "schema",
            "wifi" to "webserver",
            "finder" to "files",
            "keyboardSettings" to "keyboard",
            "inputBehavior" to "keyboard",
            "feedback" to "keyboard",
            "fonts" to "appearance",
            "keyboardSkins" to "skin",
            "toolbar" to "toolbar",
            "symbolBar" to "symbol_bar",
            "phrase" to "phrase",
            "clipboard" to "clipboard",
            "script" to "scripts",
            "rime" to "rime",
            "backup" to "backup",
            "remoteStorage" to "userdict",
        )
        assertEquals(16, expected.size)
        for ((alias, page) in expected) {
            assertEquals(
                "alias $alias",
                page,
                pageOf("jime://com.jobeen.ime/$alias"),
            )
        }
        // 第 17 项 typingPractice 已定不做：不导航。
        assertNull(pageOf("jime://com.jobeen.ime/typingPractice"))
    }

    // ---- 回落 ----

    @Test
    fun `fallback bx0 ids map to pages`() {
        val expected = mapOf(
            "skins" to "skin",
            "keyboardMenu" to "keyboardMenu",
            "files" to "files",
            "webServer" to "webserver",
            "leveldb" to "leveldb",
            "ai" to "ai",
        )
        for ((id, page) in expected) {
            assertEquals("fallback $id", page, pageOf("jime://com.jobeen.ime/$id"))
        }
    }

    @Test
    fun `catalog keys route directly`() {
        val keys = listOf(
            "schema", "scheme_update", "rime", "candidates", "skin", "scripts",
            "keyboard", "emoji", "keyboardMenu", "toolbar", "symbol_bar",
            "key_symbol", "skin_editor", "appearance", "clipboard", "phrase",
            "ai", "voice", "stats", "userdict", "leveldb", "backup", "files",
            "webserver", "about",
        )
        assertEquals(25, keys.size)
        for (key in keys) {
            assertEquals(key, pageOf("jime://com.jobeen.ime/$key"))
        }
    }

    @Test
    fun `settled not-to-do pages do not navigate`() {
        assertNull(pageOf("jime://com.jobeen.ime/pro"))
        assertNull(pageOf("jime://com.jobeen.ime/keyboardPreview"))
        assertNull(pageOf("jime://com.jobeen.ime/appIcon"))
    }

    @Test
    fun `unknown path does not navigate`() {
        assertNull(pageOf("jime://com.jobeen.ime/noSuchPage"))
    }

    @Test
    fun `phrase id param is carried`() {
        val target = resolve("jime://com.jobeen.ime/phrase?id=123")!!
        assertEquals("phrase", target.pageKey)
        assertEquals("123", target.params["id"])
    }
}
