package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.base.util.appContext
import java.io.BufferedWriter
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

    /**
     * 把待导入的词库流落盘为临时文件（带大小上限），返回临时文件。
     * 只负责文件 IO；真正的导入必须经 RimeApi 在引擎线程串行执行
     * （见 [com.jobeen.ime.engine.rime.core.RimeApi.importUserDictLive]），
     * 调用方用完后负责删除临时文件。
     */
    fun stageImportFile(stream: InputStream, textFile: String): Result<File> {
        val tempFile = File(appContext.cacheDir, textFile)
        return try {
            tempFile.outputStream().use {
                stream.copyToWithLimit(it, MAX_DICT_FILE_BYTES)
            }
            Result.success(tempFile)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    /** 单块导入的大小上限：整文件导入会长时间独占引擎线程（rime-main），
     *  大词库导入/同步期间按键只能排队等它跑完；按行切块、逐块导入后，
     *  按键最多等一块的时长。 */
    private const val IMPORT_CHUNK_MAX_BYTES = 2L * 1024 * 1024

    /**
     * 分批导入 [source]：按行切成约 2MB 的临时块文件，逐块交给 [importChunk]
     * （即 RimeApi.importUserDictLive）导入，块间 yield 让出调用方协程；
     * 块文件用完即删（失败路径同样清理）。小文件（≤单块上限）不切块、
     * 直接整文件导入，与旧行为一致。
     *
     * 返回累计导入条数；任一块返回负数即中止并返回 -1（与单文件导入的
     * 失败口径一致；此前块已导入的部分不回滚——合并导入只增不减）。
     * librime 按文件逐条合并，切块不改变最终词条集合。
     */
    suspend fun importUserDictInChunks(
        source: File,
        importChunk: suspend (File) -> Int,
    ): Int {
        if (source.length() <= IMPORT_CHUNK_MAX_BYTES) {
            return importChunk(source)
        }
        val chunks = splitImportChunks(source)
        try {
            var total = 0
            for ((index, chunk) in chunks.withIndex()) {
                if (index > 0) kotlinx.coroutines.yield()
                val count = importChunk(chunk)
                if (count < 0) return -1
                total += count
            }
            return total
        } finally {
            chunks.forEach { it.delete() }
        }
    }

    /** 按行切块（不拆行）：单行超上限时自成一块。块文件与源文件同目录；
     *  切分中途失败时删除已生成的块文件并抛出，由调用方按导入失败处理。 */
    private fun splitImportChunks(source: File): List<File> {
        val chunks = ArrayList<File>()
        val prefix = "${source.name}.chunk-${System.nanoTime()}"
        var out: BufferedWriter? = null
        var bytesInChunk = 0L
        fun closeChunk() {
            out?.close()
            out = null
            bytesInChunk = 0
        }
        try {
            source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1L
                    if (out != null && bytesInChunk + lineBytes > IMPORT_CHUNK_MAX_BYTES) {
                        closeChunk()
                    }
                    if (out == null) {
                        val chunkFile = File(source.parentFile, "$prefix-${chunks.size}")
                        chunks += chunkFile
                        out = chunkFile.bufferedWriter(Charsets.UTF_8)
                    }
                    out!!.write(line)
                    out!!.newLine()
                    bytesInChunk += lineBytes
                }
            }
        } catch (e: Exception) {
            closeChunk()
            chunks.forEach { it.delete() }
            throw e
        } finally {
            closeChunk()
        }
        return chunks
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
     *
     * 线程约束：这两个 JNI 入口（以及 [getUserDictList]）只能在引擎调度器
     * 线程上调用——它们与引擎查词/学词访问的是同一个 LevelDB，跨线程并发
     * 会损坏词库。对外一律经 RimeApi 的同名挂起函数调用，不要直接调这里。
     */
    @JvmStatic
    external fun importUserDictLive(dictName: String, textFile: String): Int

    @JvmStatic
    external fun exportUserDictLive(dictName: String, textFile: String): Int
}