package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 中英切换白名单动作（转换目标 enzh 键用）：格式名往返 + 无值规则；
 * 运行时分发（偏好写＋引擎监听重刷）依赖 Android 服务，走真机验收。
 */
class WhitelistAsciiToggleTest {

    @Test
    fun toggleAsciiModeResolvesByFormatName() {
        assertEquals(
            WhitelistAction.TOGGLE_ASCII_MODE,
            WhitelistAction.fromFormatName("toggle_ascii_mode"),
        )
    }

    @Test
    fun toggleAsciiModeCarriesNoValue() {
        assertEquals(
            WhitelistAction.ValueRule.FORBIDDEN,
            WhitelistAction.TOGGLE_ASCII_MODE.valueRule,
        )
    }

    @Test
    fun unknownFormatNameStillResolvesToNull() {
        assertNull(WhitelistAction.fromFormatName("toggle_ascii"))
        assertNull(WhitelistAction.fromFormatName("enzh"))
    }
}
