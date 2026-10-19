package com.jobeen.ime

import com.jobeen.ime.engine.rime.core.RimeApi
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RimeDaemon 的会话接收者是 `object : RimeApi by realRime` 委托对象：
 * 引擎经会话调用的每个 RimeApi 方法都必须在该类上有实现。委托转发
 * 集按编译时的接口形态生成，接口新增方法后若该类未重编，缺失的方
 * 法运行时抛 AbstractMethodError（v1.1.12.7 起空组字退格闪退实锤：
 * emitKey 缺失）。本测试按方法名核对接口全集都有声明级实现。
 */
class RimeDaemonDelegateContractTest {

    @Test
    fun delegateImplementsEveryRimeApiMethod() {
        val delegateClass = try {
            Class.forName(
                "com.jobeen.ime.engine.rime.daemon.RimeDaemon\$rimeImpl\$2\$1"
            )
        } catch (e: ClassNotFoundException) {
            fail("RimeDaemon delegate class not found (renamed?): ${e.message}")
            return
        }
        val declared = delegateClass.declaredMethods.map { it.name }.toSet()
        val missing = RimeApi::class.java.methods
            .map { it.name }
            .filter { it !in declared }
        assertTrue("delegate misses RimeApi methods: $missing", missing.isEmpty())
    }
}
