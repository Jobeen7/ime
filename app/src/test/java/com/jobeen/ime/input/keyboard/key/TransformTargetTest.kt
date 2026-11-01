package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 变换目标解析（工具项 14）：目标串 → 键内视图目标。 */
class TransformTargetTest {

    @Test
    fun missingOrBlankTargetMeansWholeKey() {
        assertEquals(TransformTarget.WHOLE_KEY, resolveTransformTarget(null))
        assertEquals(TransformTarget.WHOLE_KEY, resolveTransformTarget(""))
        assertEquals(TransformTarget.WHOLE_KEY, resolveTransformTarget("   "))
    }

    @Test
    fun knownTargetsResolve() {
        assertEquals(TransformTarget.FACE, resolveTransformTarget("face"))
        assertEquals(TransformTarget.PRESSED_FACE, resolveTransformTarget("pressedFace"))
        assertEquals(TransformTarget.LABEL, resolveTransformTarget("label"))
    }

    @Test
    fun unknownTargetReturnsNullForFallback() {
        assertNull(resolveTransformTarget("badge"))
        assertNull(resolveTransformTarget("Face"))
    }
}
