package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.base.util.appContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 带上限的流拷贝：超过 [maxBytes] 立即抛异常（调用方负责清理临时文件） */
internal fun InputStream.copyToWithLimit(out: OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw IllegalStateException("文件超过大小上限（${maxBytes / 1024 / 1024}MB）")
        }
        out.write(buffer, 0, read)
    }
    return total
}

object UserDictManager {

    /** 词典文本/快照文件的大小上限：正常用户词典远小于此值，
     *  超限基本是选错文件或服务器返回异常内容，直接拒绝避免撑爆磁盘/内存 */
    const val MAX_DICT_FILE_BYTES = 64L * 1024 * 1024

    fun restoreUserDict(stream: InputStream, snapshotFile: String): Result<Unit> {
        val tempFile = File(appContext.cacheDir, snapshotFile)
        try {
            tempFile.outputStream().use {
                stream.copyToWithLimit(it, MAX_DICT_FILE_BYTES)
            }
            val success = restoreUserDict(tempFile.absolutePath)
            return if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to restore"))
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun importUserDict(stream: InputStream, dictName: String, textFile: String): Result<Int> {
        val tempFile = File(appContext.cacheDir, textFile)
        try {
            tempFile.outputStream().use {
                stream.copyToWithLimit(it, MAX_DICT_FILE_BYTES)
            }
            val count = importUserDictLive(dictName, tempFile.absolutePath)
            return if (count >= 0) {
                Result.success(count)
            } else {
                Result.failure(
                    Exception("Failed to import from '$textFile' to '$dictName'"),
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun exportUserDict(dest: OutputStream, dictName: String, textFile: String): Result<Int> {
        val tempFile = File(appContext.cacheDir, textFile)
        try {
            val count = exportUserDict(dictName, tempFile.absolutePath)
            if (count >= 0 && tempFile.exists()) {
                tempFile.inputStream().use {
                    it.copyTo(dest)
                }
                return Result.success(count)
            } else {
                return Result.failure(
                    Exception("Failed to export '$dictName' to '$textFile'"),
                )
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @JvmStatic
    external fun getUserDictList(): Array<String>

    @JvmStatic
    external fun backupUserDict(dictName: String): Boolean

    @JvmStatic
    external fun restoreUserDict(snapshotFile: String): Boolean

    @JvmStatic
    external fun exportUserDict(dictName: String, textFile: String): Int

    @JvmStatic
    external fun importUserDict(dictName: String, textFile: String): Int

    /**
     * 热导入/热导出：直接对运行中引擎已打开的共享词库句柄做合并/读取，
     * 无需停止 Rime（停止 Rime 会触发 native finalize，导致组件注册表被清空，
     * 后续 levers 调用崩溃）。词库未被引擎打开时自动回退到经典路径。
     */
    @JvmStatic
    external fun importUserDictLive(dictName: String, textFile: String): Int

    @JvmStatic
    external fun exportUserDictLive(dictName: String, textFile: String): Int
}