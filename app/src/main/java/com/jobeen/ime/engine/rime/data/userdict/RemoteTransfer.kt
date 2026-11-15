package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.engine.rime.daemon.RimeSession
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 手动词典传输（WO52 4.5）：用户词典页的手动上传／下载改走
 * 前台服务 [RemoteTransferService]，与周期同步共享
 * WebDavSync.syncMutex（见 [WebDavSync]），同一词典不同时
 * 两路传；本文件只含纯状态机与执行器，可主机单测。
 */
enum class RemoteTransferKind { UPLOAD, DOWNLOAD }

enum class RemoteTransferStatus { RUNNING, SUCCESS, FAILED }

data class RemoteTransferState(
    val runId: Long,
    val kind: RemoteTransferKind,
    val dict: String,
    val status: RemoteTransferStatus,
    val count: Int? = null,
    val error: String? = null,
    /** 本态发出时的毫秒时间戳（供消费方判新鲜度，忽略过期终端态）。 */
    val postedAtMs: Long = 0L,
)

/** 进程级传输事件：最新一次运行的状态；null 表示尚未跑过。 */
object RemoteTransferEvents {
    private val runCounter = AtomicLong(0L)
    private val _state = MutableStateFlow<RemoteTransferState?>(null)
    val state: StateFlow<RemoteTransferState?> = _state.asStateFlow()

    /** 最后一次已被消费终端态的 runId（防旋转重建后重复弹 Toast）。 */
    private val lastConsumedRunId = AtomicLong(0L)

    fun nextRunId(): Long = runCounter.incrementAndGet()

    fun post(state: RemoteTransferState) {
        _state.value = state
    }

    /** 终端态未被消费过时返回 true 并记为已消费；否则 false。 */
    fun consumeIfNewTerminal(state: RemoteTransferState): Boolean {
        if (state.status == RemoteTransferStatus.RUNNING) return false
        return lastConsumedRunId.getAndUpdate { prev ->
            if (state.runId > prev) state.runId else prev
        } < state.runId
    }

    /** 仅供单测：复位事件流。 */
    fun resetForTest() {
        _state.value = null
        lastConsumedRunId.set(0L)
    }
}

/** 传输后端：生产实现走现有 WebDavSync（不另写传输协议）。 */
interface RemoteTransferBackend {
    suspend fun upload(dict: String): Result<Int>
    suspend fun download(dict: String, remoteFile: String): Result<Int>
}

/** 生产后端：把已建好的 RimeSession 交给 WebDavSync。 */
class WebDavTransferBackend(
    private val session: RimeSession,
) : RemoteTransferBackend {
    override suspend fun upload(dict: String): Result<Int> =
        WebDavSync.upload(dict, session)

    override suspend fun download(dict: String, remoteFile: String): Result<Int> =
        WebDavSync.download(dict, session, remoteFile)
}

/**
 * 传输执行器：一次只跑一路（jobMutex 串行；WebDAV 层另有
 * WebDavSync.syncMutex 与周期同步互斥，两层都不重传）。
 * 每路先发 RUNNING、结束发 SUCCESS／FAILED 到
 * [RemoteTransferEvents]，调用方按 state 回 Toast。
 */
class RemoteTransferRunner(
    private val backend: RemoteTransferBackend,
    private val events: RemoteTransferEvents = RemoteTransferEvents,
) {
    private val jobMutex = Mutex()

    suspend fun run(
        kind: RemoteTransferKind,
        dict: String,
        remoteFile: String? = null,
    ): RemoteTransferState {
        return jobMutex.withLock {
            val runId = events.nextRunId()
            events.post(
                RemoteTransferState(
                    runId, kind, dict, RemoteTransferStatus.RUNNING,
                    postedAtMs = System.currentTimeMillis(),
                ),
            )
            val result = when (kind) {
                RemoteTransferKind.UPLOAD -> backend.upload(dict)
                RemoteTransferKind.DOWNLOAD ->
                    backend.download(dict, remoteFile ?: WebDavSync.remoteFileName(dict))
            }
            val done = result.fold(
                onSuccess = { count ->
                    RemoteTransferState(
                        runId, kind, dict, RemoteTransferStatus.SUCCESS, count = count,
                        postedAtMs = System.currentTimeMillis(),
                    )
                },
                onFailure = { error ->
                    RemoteTransferState(
                        runId, kind, dict, RemoteTransferStatus.FAILED, error = error.message,
                        postedAtMs = System.currentTimeMillis(),
                    )
                },
            )
            events.post(done)
            done
        }
    }
}
