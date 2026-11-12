package com.jobeen.ime.engine.rime.daemon

import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.core.RimeLifecycle
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.engine.rime.core.whenReady
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RimeDaemon {
    private val realRime by lazy { Rime() }

    private val rimeImpl by lazy {
        object : RimeApi by realRime {
            // 显式转发，不依赖委托生成的转发集：转发集按本类编译时
            // 的接口形态生成，接口新增方法后本文件若未重编（增量编
            // 译），转发集会缺新方法、运行时抛 AbstractMethodError
            // （v1.1.12.7 起空组字退格闪退实锤）。RimeApi 再新增方
            // 法时必须在此同步显式转发，并有契约测试兜底
            override suspend fun emitKey(value: Int, modifiers: Int, isVirtual: Boolean) {
                realRime.emitKey(value, modifiers, isVirtual)
            }
        }
    }

    // ConcurrentHashMap：ensureEstablished 的读在锁外且位于每个按键 job 的
    // 热路径上，普通 HashMap 与锁内增删并发会读到扩容中间态。
    // 声明为 MutableMap 接口类型，让 `in` 走 Kotlin 的 containsKey 语义
    private val sessions: MutableMap<String, RimeSession> =
        java.util.concurrent.ConcurrentHashMap<String, RimeSession>()

    private val lock = ReentrantLock()

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in sessions) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            return@withLock sessions.getValue(name)
        }
        // STOPPING 期同样触发启动（E-M5）：Rime.startup() 会把请求登记
        // 为 startupPending、停机完成时重走 startup；只在 STOPPED 时
        // 启动会让停机窗口内建的会话注定失败（runOnReady 等到 STOPPED
        // 即抛 RimeStoppedException），下一次 createSession 才会自愈。
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED ||
            realRime.lifecycle.currentState == RimeLifecycle.State.STOPPING
        ) {
            realRime.startup()
        }
        val session = establish(name)
        sessions[name] = session
        return@withLock session
    }

    fun destroySession(name: String): Unit = lock.withLock {
        if (name !in sessions) {
            return
        }
        sessions.remove(name)
        if (sessions.isEmpty()) {
            realRime.finalize()
        }
    }

    suspend fun observeMessages(onMessage: suspend (RimeMessage<*>) -> Unit) {
        realRime.messageFlow.collect { onMessage(it) }
    }

    /**
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) {
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STARTING) {
            // 启动中收到的重启请求不能直接执行：finalize 与 startup 都会被
            // 状态机以「状态不符」拒绝，重启被静默丢失。排队到就绪后重走流程。
            Timber.i("restartRime deferred until startup completes")
            appScope.launch {
                runCatching { realRime.lifecycle.whenReady { } }
                if (realRime.lifecycle.currentState == RimeLifecycle.State.READY) {
                    restartRime(fullCheck)
                }
            }
            return
        }
        doRestartRime(fullCheck)
    }

    private fun doRestartRime(fullCheck: Boolean): Unit = lock.withLock {
        realRime.finalize()
        realRime.startup()
    }
}
