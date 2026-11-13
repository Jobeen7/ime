// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部署失败合成 Failure 消息（H-1）：Rime.deploy() 在 OpenCC 转换
 * 失败时抛错前，先经 nativeCreate(Deploy, ["failure"]) 合成一条
 * Failure 走正常失败通道——tracker 记失败、pendingRedeploy 置标，
 * 而不是只在消费循环里记一行日志后丢弃。
 *
 * 本文件只钉消息构造（纯函数、可 JVM 测）：deploy() 的 catch 内
 * 合成＋onNativeRestartFailed 的接线、STOPPED 后探针＋重启的接线
 * 需真机验收（OpenCC 损坏包部署失败后，键盘重现应可重试、可见报错，
 * 不再全作业被拒）。
 */
class RimeDeployFailureMessageTest {

    @Test
    fun failureStringBuildsDeployFailureMessage() {
        val message = RimeMessage.nativeCreate(
            RimeMessage.MessageType.Deploy.ordinal,
            arrayOf<Any>("failure"),
        )
        assertTrue(message is RimeMessage.DeployMessage)
        assertEquals(
            RimeMessage.DeployMessage.State.Failure,
            (message as RimeMessage.DeployMessage).state,
        )
        assertEquals(RimeMessage.MessageType.Deploy, message.messageType)
    }

    @Test
    fun allFourDeployStatesRoundTrip() {
        val expected = mapOf(
            "start" to RimeMessage.DeployMessage.State.Start,
            "success" to RimeMessage.DeployMessage.State.Success,
            "failure" to RimeMessage.DeployMessage.State.Failure,
            "finish" to RimeMessage.DeployMessage.State.Finish,
        )
        for ((raw, state) in expected) {
            val message = RimeMessage.nativeCreate(
                RimeMessage.MessageType.Deploy.ordinal,
                arrayOf<Any>(raw),
            ) as RimeMessage.DeployMessage
            assertEquals("raw=$raw", state, message.state)
        }
    }

    @Test
    fun unknownDeployStateDegradesToUnknownInsteadOfThrowing() {
        // E-L1 退化：未知状态串不丢整条通知、不抛错（nativeCreate 永不抛错）。
        val message = RimeMessage.nativeCreate(
            RimeMessage.MessageType.Deploy.ordinal,
            arrayOf<Any>("bogus"),
        )
        assertTrue(message is RimeMessage.UnknownMessage)
    }
}
