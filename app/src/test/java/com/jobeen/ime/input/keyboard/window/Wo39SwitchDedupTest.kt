package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模式切换 signature 去重（WO39 E2-1）：同名＋同类型＋同方案直接
 * 早退。去重本体在 switchToResolvedWithType，本测试钉住抽出的
 * 纯判定 isDuplicateSwitch；去重 2026-10-06 提交 29178189 引入，
 * 2026-11-01 提交 4e59b858 扩展为 Triple（名＋类型＋方案）。
 */
class Wo39SwitchDedupTest {

    private val key = Triple("T9", "pinyin", "wanxiang_lite")

    @Test
    fun sameSignatureSkips() {
        assertTrue(isDuplicateSwitch("T9", key, "T9", "pinyin", "wanxiang_lite"))
    }

    @Test
    fun differentNameContinues() {
        assertFalse(isDuplicateSwitch("T9", key, "Qwerty", "pinyin", "wanxiang_lite"))
    }

    @Test
    fun differentTypeContinues() {
        // 两类型共享同一运行时名时切类型不得被旧键早退。
        assertFalse(isDuplicateSwitch("T9", key, "T9", "alphabetic", "wanxiang_lite"))
    }

    @Test
    fun differentSchemaContinues() {
        assertFalse(isDuplicateSwitch("T9", key, "T9", "pinyin", "wanxiang"))
    }

    @Test
    fun nullTypeMatchesOnlyNullType() {
        val nullTypeKey = Triple("T9", null, "wanxiang_lite")
        assertTrue(isDuplicateSwitch("T9", nullTypeKey, "T9", null, "wanxiang_lite"))
        assertFalse(isDuplicateSwitch("T9", nullTypeKey, "T9", "pinyin", "wanxiang_lite"))
        assertFalse(isDuplicateSwitch("T9", key, "T9", null, "wanxiang_lite"))
    }

    @Test
    fun nullSchemaMatchesOnlyNullSchema() {
        val nullSchemaKey = Triple("T9", "pinyin", null)
        assertTrue(isDuplicateSwitch("T9", nullSchemaKey, "T9", "pinyin", null))
        assertFalse(isDuplicateSwitch("T9", nullSchemaKey, "T9", "pinyin", "wanxiang_lite"))
        assertFalse(isDuplicateSwitch("T9", key, "T9", "pinyin", null))
    }

    @Test
    fun noLastKeyContinues() {
        assertFalse(isDuplicateSwitch("T9", null, "T9", "pinyin", "wanxiang_lite"))
        assertFalse(isDuplicateSwitch(null, null, "T9", "pinyin", "wanxiang_lite"))
    }

    @Test
    fun sameKeyButCurrentNameDiffersRetries() {
        // attachNew 失败（factory 未注入）时 lastSwitchKey 已记新键、
        // 当前名仍是旧名：下一次同参调用必须重试，不能早退。
        assertFalse(isDuplicateSwitch("Qwerty", key, "T9", "pinyin", "wanxiang_lite"))
        // detachCurrent 后当前名为空时同理。
        assertFalse(isDuplicateSwitch(null, key, "T9", "pinyin", "wanxiang_lite"))
    }
}
