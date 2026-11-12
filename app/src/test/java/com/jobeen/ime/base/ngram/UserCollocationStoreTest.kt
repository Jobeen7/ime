package com.jobeen.ime.base.ngram

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-M1：落盘失败不静默丢学习——脏计数恢复、延时重试；修好磁盘后
 * 无需再调 flush() 即可把那批学习写盘。
 */
class UserCollocationStoreTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("collocation-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 等待条件成立（轮询），超时返回 false。 */
    private fun waitFor(timeoutMs: Long = 10_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun flushSuccess_writesPairsAndCountsFlush() {
        runWithTempDir { root ->
            val file = File(root, "collocations.tsv")
            val store = UserCollocationStore(file, flushDelayMs = 200L)
            store.learn("你好", "世界")
            store.learn("你好", "世界")
            store.flush()
            assertTrue(waitFor { store.flushCount == 1 })
            assertTrue(file.isFile)
            assertTrue(file.readText().contains("你好\t世界\t2\t"))
            assertEquals(listOf("世界" to 2), store.continuations("你好"))
        }
    }

    @Test
    fun flushFailure_restoresDirtyAndRetriesAfterDiskFixed() {
        runWithTempDir { root ->
            // 父路径是个普通文件：任何写盘都失败（mkdirs 无效、tmp 写盘抛错）
            val blocker = File(root, "blocker")
            blocker.writeText("not a dir")
            val file = File(blocker, "collocations.tsv")
            val store = UserCollocationStore(file, flushDelayMs = 200L)
            store.learn("今天", "天气")
            store.learn("今天", "天气")
            store.flush()
            // 写盘失败：flushCount 不涨、内存里的学习还在
            Thread.sleep(600)
            assertEquals(0, store.flushCount)
            assertEquals(listOf("天气" to 2), store.continuations("今天"))
            assertFalse(File(blocker, "collocations.tsv.tmp").exists())

            // 修好磁盘：删掉占位文件、建成真目录；不调 flush()，
            // 延时重试应把恢复的脏数据写盘
            assertTrue(blocker.delete())
            assertTrue(blocker.mkdirs())
            assertTrue(waitFor { store.flushCount == 1 })
            assertTrue(file.isFile)
            assertTrue(file.readText().contains("今天\t天气\t2\t"))
            assertFalse(File(blocker, "collocations.tsv.tmp").exists())
        }
    }

    @Test
    fun flushFailure_explicitFlushRetriesWithoutNewLearns() {
        runWithTempDir { root ->
            val blocker = File(root, "blocker")
            blocker.writeText("not a dir")
            val file = File(blocker, "collocations.tsv")
            val store = UserCollocationStore(file, flushDelayMs = 60_000L)
            store.learn("明天", "见面")
            store.flush()
            Thread.sleep(600)
            assertEquals(0, store.flushCount)
            // 延时重试被 60 秒节流压住：显式 flush() 必须能立即重试
            assertTrue(blocker.delete())
            assertTrue(blocker.mkdirs())
            store.flush()
            assertTrue(waitFor { store.flushCount == 1 })
            assertTrue(file.readText().contains("明天\t见面\t1\t"))
        }
    }
}
