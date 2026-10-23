package com.jobeen.ime.skin.store

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 皮肤库三个状态文件（index / active / last_good）的读写层。
 *
 * 只管 IO 与解析，不管策略：读失败区分为 [ReadResult.Missing] 与
 * [ReadResult.Corrupt] 交调用方（对账/事务）按详设口径处置；读路径
 * 绝不抛异常、绝不因文件损坏阻塞启动（详设 §2.2 规则 4）。写一律
 * 经 [AtomicFiles] 原子替换并以同一把锁串行（沿用 ThemeStore 的
 * writeMutex 先例）。
 */
class SkinLibraryStore(private val layout: SkinLibraryLayout) {

    sealed interface ReadResult<out T> {
        data class Ok<T>(val value: T) : ReadResult<T>
        object Missing : ReadResult<Nothing>
        data class Corrupt(val reason: String) : ReadResult<Nothing>
    }

    private val writeMutex = Mutex()

    suspend fun readIndex(): ReadResult<LibraryIndex> =
        readJson(layout.indexFile) { json.decodeFromString(LibraryIndex.serializer(), it) }

    suspend fun readActive(): ReadResult<ActiveRecord> =
        readJson(layout.activeFile) { json.decodeFromString(ActiveRecord.serializer(), it) }

    suspend fun readLastGood(): ReadResult<LastGoodPointer> =
        readJson(layout.lastGoodFile) { json.decodeFromString(LastGoodPointer.serializer(), it) }

    suspend fun writeIndex(index: LibraryIndex) {
        writeMutex.withLock {
            AtomicFiles.writeAtomically(
                layout.root, layout.indexFile.name,
                json.encodeToString(LibraryIndex.serializer(), index),
            )
        }
    }

    suspend fun writeActive(record: ActiveRecord) {
        writeMutex.withLock {
            AtomicFiles.writeAtomically(
                layout.root, layout.activeFile.name,
                json.encodeToString(ActiveRecord.serializer(), record),
            )
        }
    }

    suspend fun writeLastGood(pointer: LastGoodPointer) {
        writeMutex.withLock {
            AtomicFiles.writeAtomically(
                layout.root, layout.lastGoodFile.name,
                json.encodeToString(LastGoodPointer.serializer(), pointer),
            )
        }
    }

    suspend fun clearLastGood() {
        writeMutex.withLock {
            runCatching { layout.lastGoodFile.delete() }
        }
    }

    private inline fun <T> readJson(file: File, decode: (String) -> T): ReadResult<T> {
        if (!file.isFile) return ReadResult.Missing
        val text = runCatching { file.readText() }.getOrElse {
            return ReadResult.Corrupt("read_failed:${it.javaClass.simpleName}")
        }
        return runCatching { ReadResult.Ok(decode(text)) }
            .getOrElse { ReadResult.Corrupt("parse_failed:${it.javaClass.simpleName}") }
    }

    companion object {
        /** 索引类文件：可读排版 + 容忍未知字段（前向兼容）；未知字段写回时不保留。 */
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
