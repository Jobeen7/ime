// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.image

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WO36 背景预处理失败码：用户可见的 Result.Failed.reason 只放稳定码，
 * 不放原始异常消息（经 JimeImporter 的 SkinIssue 详情会进导入报告
 * 对话框原样上屏；运行期重建那路只记 DiagLog）。完整异常由 prepare
 * 的 getOrElse 记一行 DiagLog.e（本文件纯函数部分只钉码映射；
 * AndroidBackgroundPreparer.prepare 本身含位图解码，无 Robolectric
 * 测不了，见汇报）。
 */
class Wo36SkinBackgroundPreparerTest {

    @Test
    fun failureCode_knownCodesPreserved() {
        assertEquals(
            "undecodable",
            backgroundFailureCode(IllegalArgumentException("undecodable")),
        )
        assertEquals(
            "decode_failed",
            backgroundFailureCode(IllegalStateException("decode_failed")),
        )
        assertEquals(
            "compress_failed",
            backgroundFailureCode(IllegalStateException("compress_failed")),
        )
    }

    @Test
    fun failureCode_deepErrorMapperClassification() {
        assertEquals(
            "no_space",
            backgroundFailureCode(IOException("No space left on device")),
        )
        assertEquals("io", backgroundFailureCode(IOException("boom")))
    }

    @Test
    fun failureCode_unknownIsFailed() {
        assertEquals(
            "failed",
            backgroundFailureCode(IllegalArgumentException("width and height must be > 0")),
        )
    }

    @Test
    fun failureCode_errorUsesClassName() {
        assertEquals("outofmemoryerror", backgroundFailureCode(OutOfMemoryError()))
    }
}
