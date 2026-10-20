package com.jobeen.ime

import com.jobeen.ime.engine.composePredictionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预测上下文定格拼装的规格：上下文 = 落屏前基底 + 本次上屏词，
 * 与落屏时序无关；超长只截头部、commit 恒在尾部完整保留。
 */
class PredictionContextTest {

    @Test
    fun emptyBaseYieldsCommitOnly() {
        assertEquals("吃饭", composePredictionContext("", "吃饭"))
    }

    @Test
    fun baseAndCommitConcatenate() {
        assertEquals("我想吃饭", composePredictionContext("我想", "吃饭"))
    }

    @Test
    fun exactlyMaxLengthIsNotTruncated() {
        val base = "字".repeat(22)
        assertEquals(base + "吃饭", composePredictionContext(base, "吃饭"))
    }

    @Test
    fun overLengthTruncatesHeadAndKeepsWholeCommit() {
        val base = "字".repeat(30)
        val result = composePredictionContext(base, "吃饭")
        assertEquals(24, result.length)
        assertTrue(result.endsWith("吃饭"))
    }

    @Test
    fun commitLongerThanMaxKeepsCommitTail() {
        val commit = "词".repeat(30)
        val result = composePredictionContext("前缀", commit)
        assertEquals(24, result.length)
        assertEquals(commit.takeLast(24), result)
    }
}
