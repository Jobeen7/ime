// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.error

import com.jobeen.ime.error.DeepErrorMapper.DeepErrorCode
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-M1：取消永不映射——[DeepErrorMapper.classify] 遇取消直接返回 null，
 * 不解包 cause 继续归类；[DeepErrorMapper.isCancellation] 识别链上任一
 * 层的取消。状态空间按取消出现的位置分区：直接取消／取消带真异常 cause／
 * 取消被包在外层异常里／取消在链深处／无取消的对照。
 *
 * [DeepErrorMapper.userMessage] 的取消分支（链上有取消返回空串）须
 * Context 取串，JVM 单测覆盖不到（与 DeepErrorMapperTest 同款口径），
 * 只由本文件的 classify／isCancellation 断言＋调用点守卫覆盖。
 */
class DeepErrorMapperCancellationTest {

    @Test
    fun classify_bareCancellationIsNull() {
        assertNull(DeepErrorMapper.classify(CancellationException()))
        assertNull(DeepErrorMapper.classify(CancellationException("Job was cancelled")))
    }

    @Test
    fun classify_cancellationWithRealCauseIsNotUnwrapped() {
        // 旧代码把 cause 解包继续归类，取消会被错报成“请求超时”；
        // 新口径：取消本身即终点，cause 再真也不归类。
        assertNull(
            DeepErrorMapper.classify(
                CancellationException("cancelled", SocketTimeoutException("timed out")),
            ),
        )
        assertNull(
            DeepErrorMapper.classify(
                CancellationException("cancelled", UnknownHostException("example.com")),
            ),
        )
    }

    @Test
    fun classify_cancellationWrappedInOuterExceptionIsNull() {
        // runCatching 之外的包住形态：外层不是取消，cause 链里是取消。
        assertNull(
            DeepErrorMapper.classify(RuntimeException("wrap", CancellationException())),
        )
        assertNull(
            DeepErrorMapper.classify(
                RuntimeException(
                    "wrap",
                    CancellationException("c", UnknownHostException("example.com")),
                ),
            ),
        )
    }

    @Test
    fun classify_cancellationDeepInChainIsNull() {
        assertNull(
            DeepErrorMapper.classify(
                RuntimeException(
                    "outer",
                    RuntimeException("middle", CancellationException()),
                ),
            ),
        )
    }

    @Test
    fun classify_nonCancellationChainStillWalks() {
        // 对照：无取消的链照旧逐层归类，不因取消守卫短路。
        assertEquals(
            DeepErrorCode.NETWORK,
            DeepErrorMapper.classify(RuntimeException("wrap", UnknownHostException("x"))),
        )
        assertEquals(
            DeepErrorCode.TIMEOUT,
            DeepErrorMapper.classify(SocketTimeoutException("timed out")),
        )
        assertNull(DeepErrorMapper.classify(RuntimeException("boom")))
        assertNull(DeepErrorMapper.classify(null))
    }

    @Test
    fun isCancellation_directAndWrapped() {
        assertTrue(DeepErrorMapper.isCancellation(CancellationException()))
        assertTrue(
            DeepErrorMapper.isCancellation(RuntimeException("wrap", CancellationException())),
        )
        assertTrue(
            DeepErrorMapper.isCancellation(
                RuntimeException(
                    "outer",
                    RuntimeException("middle", CancellationException()),
                ),
            ),
        )
    }

    @Test
    fun isCancellation_falseForPlainFailures() {
        assertFalse(DeepErrorMapper.isCancellation(RuntimeException("boom")))
        assertFalse(
            DeepErrorMapper.isCancellation(RuntimeException("wrap", UnknownHostException("x"))),
        )
        assertFalse(DeepErrorMapper.isCancellation(null))
    }
}
