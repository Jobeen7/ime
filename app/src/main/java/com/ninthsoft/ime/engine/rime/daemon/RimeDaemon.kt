package com.ninthsoft.ime.engine.rime.daemon

import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.ninthsoft.ime.R
import com.ninthsoft.ime.engine.rime.core.Rime
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.core.RimeLifecycle
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.core.lifecycleScope
import com.ninthsoft.ime.engine.rime.core.whenReady
import com.ninthsoft.ime.base.util.appContext
import com.ninthsoft.ime.base.util.appScope
import com.ninthsoft.ime.base.util.createNotificationChannel
import com.ninthsoft.ime.base.util.subProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import splitties.systemservices.notificationManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RimeDaemon {
    private val realRime by lazy { Rime() }

    private val rimeImpl by lazy { object : RimeApi by realRime {} }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in sessions) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
        }

        override fun runIfReady(block: suspend RimeApi.() -> Unit) {
            ensureEstablished {
                if (realRime.isReady) {
                    realRime.lifecycleScope.launch { block(rimeImpl) }
                }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            return@withLock sessions.getValue(name)
        }
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED) {
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
        sessions -= name
        if (sessions.isEmpty()) {
            realRime.finalize()
        }
    }

    suspend fun awaitMessage(predicate: (RimeMessage<*>) -> Boolean): RimeMessage<*> =
        realRime.messageFlow.first(predicate)

    suspend fun observeMessages(onMessage: suspend (RimeMessage<*>) -> Unit) {
        realRime.messageFlow.collect { onMessage(it) }
    }

    private const val CHANNEL_ID = "rime-daemon"
    private const val MESSAGE_ID = 2331
    private var restartId = 0

    init {
        createNotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.app_name),
        )
        appScope.launch {
            realRime.messageFlow.collect {
                handleRimeMessage(it)
            }
        }
    }

    private inline fun sendNotification(
        id: Int,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.app_name))
        builder.buildAction()
        builder.build().let { notificationManager.notify(id, it) }
    }

    /**
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) = lock.withLock {
        val id = restartId++
        if (!fullCheck) {
            sendNotification(id) {
                setContentTitle(appContext.getString(R.string.app_name))
                setContentText(appContext.getString(R.string.rime_restarting))
                setOngoing(true)
                setSmallIcon(R.mipmap.ic_launcher_round)
                setProgress(100, 0, true)
                setPriority(NotificationCompat.PRIORITY_HIGH)
            }
        }
        realRime.finalize()
        realRime.startup()
        appScope.launch {
            realRime.lifecycle.whenReady {
                notificationManager.cancel(id)
            }
        }
    }

    private suspend fun handleRimeMessage(it: RimeMessage<*>) {
        if (it is RimeMessage.DeployMessage) {
            val buildNotification: NotificationCompat.Builder.() -> Unit
            when (it.data) {
                RimeMessage.DeployMessage.State.Start -> {
                    buildNotification = {
                        setContentText(appContext.getString(R.string.deploy_progress))
                        setProgress(0, 0, true)
                        setOngoing(true)
                        setAutoCancel(false)
                        setSmallIcon(R.mipmap.ic_launcher_round)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                    withContext(Dispatchers.IO) { subProcess("logcat", "--clear") }
                }

                RimeMessage.DeployMessage.State.Success -> {
                    buildNotification = {
                        setColor(Color.GREEN)
                        setContentText(appContext.getString(R.string.rime_deploy_success))
                        setOngoing(false)
                        setTimeoutAfter(3000L)
                        setAutoCancel(true)
                        setSmallIcon(R.mipmap.ic_launcher_round)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                }

                RimeMessage.DeployMessage.State.Failure -> {
                    buildNotification = {
                        setColor(Color.GREEN)
                        setContentText(appContext.getString(R.string.rime_deploy_failure))
                        setOngoing(false)
                        setTimeoutAfter(3000L)
                        setAutoCancel(true)
                        setSmallIcon(R.mipmap.ic_launcher_round)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                }

                else -> return
            }
            sendNotification(MESSAGE_ID, buildNotification)
        }
    }
}
