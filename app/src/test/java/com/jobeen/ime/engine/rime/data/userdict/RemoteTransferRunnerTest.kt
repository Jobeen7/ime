package com.jobeen.ime.engine.rime.data.userdict

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WO52 4.5：传输执行器（纯 JVM）：状态流转、失败、串行互斥。
 * 后端用 fake；生产后端 WebDavTransferBackend 只转发 WebDavSync，
 * 其与周期同步的互斥靠 WebDavSync.syncMutex（两路都经它）。
 */
class RemoteTransferRunnerTest {

    private class FakeBackend(
        var uploadResult: Result<Int> = Result.success(3),
        var downloadResult: Result<Int> = Result.success(5),
        var delayMs: Long = 0L,
    ) : RemoteTransferBackend {
        val maxConcurrent = AtomicInteger(0)
        private val current = AtomicInteger(0)
        val uploadCalls = mutableListOf<String>()
        val downloadCalls = mutableListOf<Pair<String, String>>()
        var stateAtUploadStart: RemoteTransferState? = null

        private suspend fun track(block: suspend () -> Unit) {
            val now = current.incrementAndGet()
            maxConcurrent.updateAndGet { max -> maxOf(max, now) }
            try {
                if (delayMs > 0) delay(delayMs)
                block()
            } finally {
                current.decrementAndGet()
            }
        }

        override suspend fun upload(dict: String): Result<Int> {
            stateAtUploadStart = RemoteTransferEvents.state.value
            var result: Result<Int> = uploadResult
            track { uploadCalls.add(dict); result = uploadResult }
            return result
        }

        override suspend fun download(dict: String, remoteFile: String): Result<Int> {
            var result: Result<Int> = downloadResult
            track { downloadCalls.add(dict to remoteFile); result = downloadResult }
            return result
        }
    }

    @Before
    fun setUp() {
        RemoteTransferEvents.resetForTest()
    }

    @After
    fun tearDown() {
        RemoteTransferEvents.resetForTest()
    }

    @Test
    fun `upload success posts running then success with count`() = runBlocking {
        val backend = FakeBackend()
        val runner = RemoteTransferRunner(backend)
        // 后端被调用瞬间 state 必为 RUNNING（执行器先发 RUNNING 再调后端）；
        // StateFlow 会合并快速连续发射，live 订阅测不到中间态，故测此确定性点。
        val done = runner.run(RemoteTransferKind.UPLOAD, "wanxiang_lite")
        assertEquals(RemoteTransferStatus.SUCCESS, done.status)
        assertEquals(3, done.count)
        assertEquals(RemoteTransferStatus.RUNNING, backend.stateAtUploadStart?.status)
        assertEquals(RemoteTransferStatus.SUCCESS, RemoteTransferEvents.state.value?.status)
        assertEquals(listOf("wanxiang_lite"), backend.uploadCalls)
    }

    @Test
    fun `download failure posts failed with error`() = runBlocking {
        val backend = FakeBackend(downloadResult = Result.failure(RuntimeException("boom")))
        val runner = RemoteTransferRunner(backend)
        val done = runner.run(RemoteTransferKind.DOWNLOAD, "wanxiang_lite", "wanxiang_lite.userdict.txt")
        assertEquals(RemoteTransferStatus.FAILED, done.status)
        assertEquals("boom", done.error)
        assertEquals(listOf("wanxiang_lite" to "wanxiang_lite.userdict.txt"), backend.downloadCalls)
    }

    @Test
    fun `download without remote file defaults to dict userdict name`() = runBlocking {
        val backend = FakeBackend()
        val runner = RemoteTransferRunner(backend)
        runner.run(RemoteTransferKind.DOWNLOAD, "wanxiang_lite")
        assertEquals(
            listOf("wanxiang_lite" to "wanxiang_lite.userdict.txt"),
            backend.downloadCalls,
        )
    }

    @Test
    fun `two concurrent runs are serialized`() = runBlocking {
        val backend = FakeBackend(delayMs = 100L)
        val runner = RemoteTransferRunner(backend)
        val a = async { runner.run(RemoteTransferKind.UPLOAD, "a") }
        val b = async { runner.run(RemoteTransferKind.DOWNLOAD, "b", "b.userdict.txt") }
        assertEquals(RemoteTransferStatus.SUCCESS, a.await().status)
        assertEquals(RemoteTransferStatus.SUCCESS, b.await().status)
        assertEquals(1, backend.maxConcurrent.get())
    }

    @Test
    fun `terminal state is consumed only once`() {
        val state = RemoteTransferState(
            1L, RemoteTransferKind.UPLOAD, "a", RemoteTransferStatus.SUCCESS, count = 1,
        )
        assertTrue(RemoteTransferEvents.consumeIfNewTerminal(state))
        assertFalse(RemoteTransferEvents.consumeIfNewTerminal(state))
        assertFalse(
            RemoteTransferEvents.consumeIfNewTerminal(
                RemoteTransferState(
                    1L, RemoteTransferKind.UPLOAD, "a", RemoteTransferStatus.RUNNING,
                ),
            ),
        )
    }

    @Test
    fun `run ids are monotonic`() {
        assertTrue(RemoteTransferEvents.nextRunId() < RemoteTransferEvents.nextRunId())
    }
}
