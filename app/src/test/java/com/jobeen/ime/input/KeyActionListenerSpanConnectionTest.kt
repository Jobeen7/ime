// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 清组字 span 的连接选择（I-L4）：桥接激活时组字 span 在虚拟连接
 * 上（active），否则在真实连接上（current）。键直发目标不在此
 * 函数内——它恒走真实连接，有意与清 span 的连接分开。
 *
 * 夹具用动态代理造 InputConnection（纯 JVM 测不了 Android 服务
 * 分发，代理只做身份比对、不调任何方法）；分支在服务分发内的接线
 * 需真机验收（桥接模式下 AppKeyEventAction 后虚拟连接旧组字 span
 * 不残留）。
 */
class KeyActionListenerSpanConnectionTest {

    private fun fakeConnection(): InputConnection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf<Class<*>>(InputConnection::class.java),
        ) { _, _, _ -> null } as InputConnection

    @Test
    fun bridgedModeClearsVirtualConnection() {
        val active = fakeConnection()
        val current = fakeConnection()
        assertSame(active, resolveSpanClearConnection(active, current))
    }

    @Test
    fun nonBridgedModeFallsBackToCurrent() {
        val current = fakeConnection()
        assertSame(current, resolveSpanClearConnection(null, current))
    }

    @Test
    fun bothNullReturnsNull() {
        assertNull(resolveSpanClearConnection(null, null))
    }

    @Test
    fun activeWinsEvenWhenCurrentDiffers() {
        // 同一对象时亦然：active 恒优先，不按 current 误清。
        // 代理只做身份比对：assertEquals 会调代理的 equals（handler
        // 返回 null、拆箱 NPE），此处恒用 assertSame（其余三测同）。
        val active = fakeConnection()
        assertSame(active, resolveSpanClearConnection(active, active))
    }
}
