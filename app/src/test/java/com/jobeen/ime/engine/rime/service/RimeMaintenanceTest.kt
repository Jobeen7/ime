// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.service

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.engine.rime.core.RimeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.lang.reflect.Modifier

/**
 * WO52 3.1b/3.1c：跨进程部署/同步链的主机可测部分。
 *
 * 覆盖：执行体入口单飞（同时触发不重入）、客户端远端调用／
 * 绑定失败回落本进程／远端调用中失败不回落、syncUserData 的
 * JNI 绑定约定（类名＋方法名＋签名）与 RimeApi 声明。
 * 真机待验：Service 实际绑定与跨进程调用、JNI 直通 native、
 * 远端部署后本进程引擎收敛、两进程共用用户目录的 LevelDB 行为。
 */
class RimeMaintenanceTest {

    // ---- fakes ----

    private class FakeOps(
        var deployResult: DeployAwaitResult = DeployAwaitResult.COMPLETED,
        var syncResult: Boolean = true,
    ) : RimeMaintenanceOps {
        var deployCalls = 0
        var syncCalls = 0
        var lastTimeoutMs: Long = -1
        val deployStarted = CompletableDeferred<Unit>()
        val releaseDeploy = CompletableDeferred<Unit>()
        var blockDeploy = false

        override suspend fun deploy(timeoutMs: Long): DeployAwaitResult {
            deployCalls++
            lastTimeoutMs = timeoutMs
            deployStarted.complete(Unit)
            if (blockDeploy) releaseDeploy.await()
            return deployResult
        }

        override suspend fun syncUserData(): Boolean {
            syncCalls++
            return syncResult
        }
    }

    private object FakeBinder : IBinder {
        override fun getInterfaceDescriptor(): String? = null
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<String>?) = Unit
        override fun dumpAsync(fd: FileDescriptor, args: Array<String>?) = Unit
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = false
    }

    private class FakeRemote(
        var deployResult: Boolean = true,
        var syncResult: Boolean = true,
        var deployThrows: Boolean = false,
    ) : IRimeService {
        var deployCalls = 0
        var syncCalls = 0

        override fun deploy(): Boolean {
            deployCalls++
            if (deployThrows) throw RemoteException("fake remote failure")
            return deployResult
        }

        override fun syncUserData(): Boolean {
            syncCalls++
            return syncResult
        }

        override fun isBusy(): Boolean = false
        override fun asBinder(): IBinder = FakeBinder
    }

    private class FakeConnector(
        private val remote: IRimeService?,
    ) : RimeServiceConnector {
        var disconnectCalls = 0

        override suspend fun connect(): IRimeService? = remote
        override fun disconnect() {
            disconnectCalls++
        }
    }

    // ---- 3.1b 执行体：入口单飞 ----

    @Test
    fun executor_deployCompleted_true() = runBlocking {
        val ops = FakeOps()
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        assertTrue(executor.deploy())
        assertEquals(1, ops.deployCalls)
        assertEquals(1_000L, ops.lastTimeoutMs)
    }

    @Test
    fun executor_deployFailed_false() = runBlocking {
        val ops = FakeOps(deployResult = DeployAwaitResult.FAILED)
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        assertFalse(executor.deploy())
    }

    @Test
    fun executor_deployTimeoutRunning_false() = runBlocking {
        val ops = FakeOps(deployResult = DeployAwaitResult.TIMEOUT_RUNNING)
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        assertFalse(executor.deploy())
    }

    @Test
    fun executor_concurrentDeploy_secondReturnsFalseImmediately() = runBlocking {
        val ops = FakeOps().apply { blockDeploy = true }
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        val first = async(Dispatchers.Default) { executor.deploy() }
        ops.deployStarted.await()
        assertTrue(executor.isBusy())
        // 第二路不排队、不重入：立即返回 false，执行体只进一次。
        assertFalse(executor.deploy())
        assertEquals(1, ops.deployCalls)
        ops.releaseDeploy.complete(Unit)
        assertTrue(first.await())
        assertFalse(executor.isBusy())
        // 释放后可再进。
        assertTrue(executor.deploy())
        assertEquals(2, ops.deployCalls)
    }

    @Test
    fun executor_syncWhileDeployBusy_returnsFalse() = runBlocking {
        val ops = FakeOps().apply { blockDeploy = true }
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        val first = async(Dispatchers.Default) { executor.deploy() }
        ops.deployStarted.await()
        assertFalse(executor.syncUserData())
        assertEquals(0, ops.syncCalls)
        ops.releaseDeploy.complete(Unit)
        assertTrue(first.await())
    }

    @Test
    fun executor_syncDelegatesToOps() = runBlocking {
        val ops = FakeOps(syncResult = true)
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        assertTrue(executor.syncUserData())
        assertEquals(1, ops.syncCalls)
    }

    @Test
    fun executor_syncFalse_propagates() = runBlocking {
        val ops = FakeOps(syncResult = false)
        val executor = RimeMaintenanceExecutor(ops, 1_000L)
        assertFalse(executor.syncUserData())
    }

    // ---- 3.1b 客户端：远端 / 回落 / 调用中失败 ----

    @Test
    fun client_deploy_remoteSuccess() = runBlocking {
        val remote = FakeRemote(deployResult = true)
        val local = FakeOps()
        val connector = FakeConnector(remote)
        val client = RimeMaintenanceClient(connector, local)
        assertTrue(client.deploy(1_000L))
        assertEquals(RimeMaintenanceClient.Transport.REMOTE, client.lastTransport)
        assertEquals(1, remote.deployCalls)
        assertEquals(0, local.deployCalls)
        assertEquals(1, connector.disconnectCalls)
    }

    @Test
    fun client_deploy_remoteFalse_noFallback() = runBlocking {
        val remote = FakeRemote(deployResult = false)
        val local = FakeOps()
        val client = RimeMaintenanceClient(FakeConnector(remote), local)
        assertFalse(client.deploy(1_000L))
        assertEquals(RimeMaintenanceClient.Transport.REMOTE, client.lastTransport)
        assertEquals(0, local.deployCalls)
    }

    @Test
    fun client_deploy_remoteThrows_noLocalFallback() = runBlocking {
        val remote = FakeRemote(deployThrows = true)
        val local = FakeOps()
        val client = RimeMaintenanceClient(FakeConnector(remote), local)
        assertFalse(client.deploy(1_000L))
        assertEquals(RimeMaintenanceClient.Transport.REMOTE, client.lastTransport)
        assertEquals(0, local.deployCalls)
    }

    @Test
    fun client_deploy_bindFails_fallsBackToLocal() = runBlocking {
        val local = FakeOps(deployResult = DeployAwaitResult.COMPLETED)
        val connector = FakeConnector(null)
        val client = RimeMaintenanceClient(connector, local)
        assertTrue(client.deploy(2_000L))
        assertEquals(RimeMaintenanceClient.Transport.LOCAL_FALLBACK, client.lastTransport)
        assertEquals(1, local.deployCalls)
        assertEquals(2_000L, local.lastTimeoutMs)
    }

    @Test
    fun client_deploy_bindFails_localFailed_false() = runBlocking {
        val local = FakeOps(deployResult = DeployAwaitResult.FAILED)
        val client = RimeMaintenanceClient(FakeConnector(null), local)
        assertFalse(client.deploy(1_000L))
        assertEquals(RimeMaintenanceClient.Transport.LOCAL_FALLBACK, client.lastTransport)
    }

    @Test
    fun client_syncUserData_remote() = runBlocking {
        val remote = FakeRemote(syncResult = true)
        val local = FakeOps()
        val client = RimeMaintenanceClient(FakeConnector(remote), local)
        assertTrue(client.syncUserData())
        assertEquals(RimeMaintenanceClient.Transport.REMOTE, client.lastTransport)
        assertEquals(1, remote.syncCalls)
        assertEquals(0, local.syncCalls)
    }

    @Test
    fun client_syncUserData_bindFails_fallsBackToLocal() = runBlocking {
        val local = FakeOps(syncResult = true)
        val client = RimeMaintenanceClient(FakeConnector(null), local)
        assertTrue(client.syncUserData())
        assertEquals(RimeMaintenanceClient.Transport.LOCAL_FALLBACK, client.lastTransport)
        assertEquals(1, local.syncCalls)
    }

    // ---- 3.1c syncUserData：JNI 绑定约定 ----

    @Test
    fun syncUserData_declaredOnRimeApi() {
        // suspend fun 编译为 syncUserData(Continuation)：按名找，
        // 断言单参 Continuation（即 Kotlin 侧无参、返 Boolean 的挂起函数）。
        val method = RimeApi::class.java.methods.single { it.name == "syncUserData" }
        assertEquals(1, method.parameterCount)
        assertTrue(
            kotlin.coroutines.Continuation::class.java
                .isAssignableFrom(method.parameterTypes[0]),
        )
    }

    @Test
    fun syncUserData_jniBindingMatchesNativeExport() {
        // JNI 导出名 Java_com_jobeen_ime_engine_rime_core_Rime_syncUserData
        // 要求 Rime 类上有 public static、无参、返 boolean 的 syncUserData。
        // initialize=false：只读方法元数据，不跑 <clinit>（不加载 .so）。
        val rimeClass = Class.forName(
            "com.jobeen.ime.engine.rime.core.Rime",
            false,
            RimeMaintenanceTest::class.java.classLoader,
        )
        val method = rimeClass.getMethod("syncUserData")
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
        assertEquals(0, method.parameterCount)
    }
}
