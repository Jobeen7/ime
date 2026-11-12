// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Commit 提交记账决策（I-M5，上一位代理引入、本轮验证补测）。 */
class CommitEffectsTest {

    @Test
    fun committedNonEmptyCommitDoesAllThree() {
        assertEquals(
            CommitEffects(noteStats = true, recordUndo = true, invalidateCleared = true),
            commitEffects(true, "你好"),
        )
    }

    @Test
    fun committedEmptyCommitSkipsClearedInvalidation() {
        // 空提交不作废清字账（无新文字落屏，清掉的文字仍可恢复）。
        assertEquals(
            CommitEffects(noteStats = true, recordUndo = true, invalidateCleared = false),
            commitEffects(true, ""),
        )
    }

    @Test
    fun failedCommitDoesNothing() {
        // 连接已死／只读框致提交失败：不记数、不入撤销账、不作废清字账。
        assertEquals(
            CommitEffects(noteStats = false, recordUndo = false, invalidateCleared = false),
            commitEffects(false, "你好"),
        )
        assertEquals(
            CommitEffects(noteStats = false, recordUndo = false, invalidateCleared = false),
            commitEffects(false, ""),
        )
    }
}

/** 场景强制键盘是否按名重显（I-S1，上一位代理引入、本轮验证补测）。 */
class ForcedKeyboardReshowTest {

    @Test
    fun reshowOnlyWhenForcedNameEqualsCurrentName() {
        assertTrue(isForcedKeyboardReshow("Number", "Number"))
        assertTrue(isForcedKeyboardReshow("Qwerty", "Qwerty"))
    }

    @Test
    fun noReshowWhenNotForcedOrSwitchedAway() {
        assertFalse(isForcedKeyboardReshow(null, "Number"))
        assertFalse(isForcedKeyboardReshow("Number", "Qwerty"))
        assertFalse(isForcedKeyboardReshow("Qwerty", null))
        assertFalse(isForcedKeyboardReshow(null, null))
    }
}
