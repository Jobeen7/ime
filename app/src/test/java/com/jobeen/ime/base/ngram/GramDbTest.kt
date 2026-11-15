package com.jobeen.ime.base.ngram

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** B-S3：GramDb 构造时校验文件头长度与数组边界，损坏模型判无效不崩溃。 */
class GramDbTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("gram-db-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 按 .gram 文件头布局造文件：32 字节格式串＋36 处 int＋40 处 int。 */
    private fun gramFile(
        dir: File,
        name: String = "test.gram",
        format: String = "Rime::Grammar/test",
        arraySize: Int = 0,
        arrayOffset: Int = 0,
        totalSize: Int = 44,
    ): File {
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        val fmtBytes = format.toByteArray(Charsets.UTF_8)
        buf.put(fmtBytes.copyOf(minOf(32, totalSize)))
        if (totalSize >= 40) {
            buf.position(36)
            buf.putInt(arraySize)
        }
        if (totalSize >= 44) {
            buf.putInt(arrayOffset)
        }
        val file = File(dir, name)
        file.writeBytes(buf.array())
        return file
    }

    private fun expectInvalid(file: File) {
        try {
            GramDb(file.absolutePath)
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException for ${file.name} (${file.length()} bytes)")
    }

    @Test
    fun minimalValid_loadsAndQueriesEmpty() {
        runWithTempDir { root ->
            val file = gramFile(root)
            val db = GramDb(file.absolutePath)
            assertTrue(db.lookup("你好", "世界").isEmpty())
            assertEquals(0.0, db.query("你好", "世界"), 0.0)
        }
    }


    @Test
    fun negativeArraySizeOrOffset_rejected() {
        runWithTempDir { root ->
            expectInvalid(gramFile(root, "neg-size.gram", arraySize = -1))
            expectInvalid(gramFile(root, "neg-off.gram", arrayOffset = -1))
        }
    }

    @Test
    fun arrayStartBeyondFile_rejected() {
        runWithTempDir { root ->
            // 40 + 1000 远超 44 字节文件
            expectInvalid(gramFile(root, "off.gram", arrayOffset = 1000))
        }
    }

    @Test
    fun arrayLengthBeyondFile_rejected() {
        runWithTempDir { root ->
            // 40 + 0 合理，但 arraySize*4 = 400 字节越出文件
            expectInvalid(gramFile(root, "big.gram", arraySize = 100))
            // Int 溢出探针：arraySize 取 Int 最大值，32 位乘法会绕回
            // 负数，Long 口径必须仍判无效
            expectInvalid(gramFile(root, "overflow.gram", arraySize = Int.MAX_VALUE))
        }
    }

}
