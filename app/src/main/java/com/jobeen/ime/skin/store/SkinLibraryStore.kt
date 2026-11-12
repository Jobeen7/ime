package com.jobeen.ime.skin.store

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 皮肤库三个状态文件（index / active / last_good）的读写层。
 *
 * 只管 IO 与解析，不管策略：读失败区分为 [ReadResult.Missing] 与
 * [ReadResult.Corrupt] 交调用方（对账/事务）按详设口径处置；读路径
 * 绝不抛异常、绝不因文件损坏阻塞启动（详设 §2.2 规则 4）。写一律
 * 经 [AtomicFiles] 原子替换并以同一把锁串行（沿用 ThemeStore 的
 * writeMutex 先例）。
 *
 * 提交锁（2026-11-12 审计 K-M2）：索引读-改-写必须整段串行——
 * 导入／对账／编辑／删除各持自己的锁时，滞后写回会互相覆盖
 * （复活已删条目、丢编辑器新代指针）。[withCommitLock] 是库根
 * 共享的唯一提交锁，全部索引读-改-写都走它；[updateIndex] 把
 * “重读→变换→写回”包成一次提交，变换返回 null 即不写（调用方
 * 据此判定并发冲突）。
 */
class SkinLibraryStore(private val layout: SkinLibraryLayout) {

    sealed interface ReadResult<out T> {
        data class Ok<T>(val value: T) : ReadResult<T>
        object Missing : ReadResult<Nothing>
        data class Corrupt(val reason: String) : ReadResult<Nothing>
    }

    private val writeMutex = Mutex()

    /**
     * 在提交锁内执行一段索引读-改-写：与导入事务、对账、编辑器、
     * 删除共用同一把按库根共享的锁（见伴生对象）。调用方不得在
     * 块内再调 [withCommitLock]／[updateIndex]（Mutex 不可重入）。
     */
    suspend fun <T> withCommitLock(block: suspend () -> T): T =
        commitLockFor(layout.root).withLock { block() }

    /**
     * 原子索引更新：提交锁内重读当前索引、变换、写回；变换返回
     * null 时不写并返回 null（并发冲突／前置条件不再成立时用）。
     * 缺失索引按空索引变换；损坏索引返回 null（不凭猜测写回，
     * 由启动对账重建）。
     */
    suspend fun updateIndex(transform: (LibraryIndex) -> LibraryIndex?): LibraryIndex? =
        withCommitLock {
            val current = when (val read = readIndex()) {
                is ReadResult.Ok -> read.value
                is ReadResult.Missing -> LibraryIndex()
                is ReadResult.Corrupt -> return@withCommitLock null
            }
            val updated = transform(current) ?: return@withCommitLock null
            writeIndex(updated)
            updated
        }

    suspend fun readIndex(): ReadResult<LibraryIndex> =
        readJson(layout.indexFile) { json.decodeFromString(LibraryIndex.serializer(), it) }

    suspend fun readActive(): ReadResult<ActiveRecord> =
        readJson(layout.activeFile) { json.decodeFromString(ActiveRecord.serializer(), it) }

    suspend fun readLastGood(): ReadResult<LastGoodPointer> =
        readJson(layout.lastGoodFile) { json.decodeFromString(LastGoodPointer.serializer(), it) }

    suspend fun writeIndex(index: LibraryIndex) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                AtomicFiles.writeAtomically(
                    layout.root, layout.indexFile.name,
                    json.encodeToString(LibraryIndex.serializer(), index),
                )
            }
        }
    }

    suspend fun writeActive(record: ActiveRecord) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                AtomicFiles.writeAtomically(
                    layout.root, layout.activeFile.name,
                    json.encodeToString(ActiveRecord.serializer(), record),
                )
            }
        }
    }

    suspend fun writeLastGood(pointer: LastGoodPointer) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                AtomicFiles.writeAtomically(
                    layout.root, layout.lastGoodFile.name,
                    json.encodeToString(LastGoodPointer.serializer(), pointer),
                )
            }
        }
    }

    suspend fun clearLastGood() {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { layout.lastGoodFile.delete() }
            }
        }
    }

    /**
     * 读＋解析一律在 IO 线程：调用方在主线程时也不阻塞 UI
     *（审计 #2：此前 readText＋decode 全在调用线程上）。
     */
    private suspend inline fun <T> readJson(file: File, crossinline decode: (String) -> T): ReadResult<T> {
        if (!file.isFile) return ReadResult.Missing
        return withContext(Dispatchers.IO) {
            val text = runCatching { file.readText() }.getOrElse {
                return@withContext ReadResult.Corrupt("read_failed:${it.javaClass.simpleName}")
            }
            runCatching { ReadResult.Ok(decode(text)) }
                .getOrElse { ReadResult.Corrupt("parse_failed:${it.javaClass.simpleName}") }
        }
    }

    companion object {
        /** 索引类文件：可读排版 + 容忍未知字段（前向兼容）；未知字段写回时不保留。 */
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 提交锁按库根目录共享（2026-11-12 审计 K-M2，原属
         * ImportTransaction 的同名实现上移至此）：生产每次 import
         * 都新建 ImportTransaction，实例级锁拦不住跨实例并行；
         * 对账／编辑／删除的索引读-改-写同样须进同一把锁。
         * 单把锁、无嵌套，故无死锁；表项按库根数增长（生产 1 个），不回收。
         */
        private val commitLocks = ConcurrentHashMap<String, Mutex>()

        private fun commitLockFor(root: File): Mutex =
            commitLocks.getOrPut(root.absolutePath) { Mutex() }
    }
}
