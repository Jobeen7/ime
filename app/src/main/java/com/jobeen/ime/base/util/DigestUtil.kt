package com.jobeen.ime.base.util

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * SHA-256 摘要的统一实现（原先散落在各下载/更新管理器中的五处内联
 * 拷贝收敛至此，杜绝口径漂移）。
 *
 * 口径与被收敛的各实现逐字节一致：小写十六进制、`%02x` 逐字节格式
 * 化；文件版流式读取（32KB 缓冲），大文件（语法模型约 400MB）不整
 * 读入内存；[sha256HexCancellable] 每 32 个缓冲块（即每 1MB）检查一
 * 次协程取消，与 WanxiangUpdateManager 原实现的检查节奏相同。
 */
object DigestUtil {

    private const val BUFFER_SIZE = 32 * 1024

    /** 每多少个缓冲块检查一次协程取消：32 × 32KB = 1MB。 */
    private const val CANCEL_CHECK_CHUNKS = 32

    fun newSha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** 字节数组 → 小写十六进制串（与各历史实现相同的 `%02x` 逐字节格式化）。 */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** 结束一个增量喂入的摘要并输出十六进制（会消费该 [MessageDigest]）。 */
    fun hexDigest(digest: MessageDigest): String = toHex(digest.digest())

    fun sha256Hex(data: ByteArray): String {
        val digest = newSha256Digest()
        digest.update(data)
        return hexDigest(digest)
    }

    /** 文件内容 SHA-256（流式读取）。读失败抛 IOException，语义与历史实现一致。 */
    fun sha256Hex(file: File): String {
        val digest = newSha256Digest()
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return hexDigest(digest)
    }

    /**
     * 同 [sha256Hex]（File），但每 1MB 检查一次协程取消，
     * 供在协程中对大文件算摘要、页面退出后不空转的场景使用。
     */
    suspend fun sha256HexCancellable(file: File): String {
        val digest = newSha256Digest()
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            var chunks = 0
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
                if (++chunks % CANCEL_CHECK_CHUNKS == 0) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        return hexDigest(digest)
    }
}
