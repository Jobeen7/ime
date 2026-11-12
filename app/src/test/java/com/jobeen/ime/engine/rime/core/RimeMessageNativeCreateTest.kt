// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RimeMessage.nativeCreate 硬化（E-L1）：未知类型码／未知部署状态串／
 * 参数形态不符时退化为 UnknownMessage 交给下游忽略，而不是在分发点
 * 整条丢弃（丢 Deploy 通知会让 deployAndAwait 靠 10 秒超时按成功处理）。
 * 本函数永不抛错。
 *
 * MessageType 顺序：Unknown(0) Schema(1) Option(2) Deploy(3) …
 */
class RimeMessageNativeCreateTest {

    @Test
    fun validDeployStatesParse() {
        val expected = mapOf(
            "start" to RimeMessage.DeployMessage.State.Start,
            "success" to RimeMessage.DeployMessage.State.Success,
            "failure" to RimeMessage.DeployMessage.State.Failure,
            "finish" to RimeMessage.DeployMessage.State.Finish,
        )
        for ((raw, state) in expected) {
            val msg = RimeMessage.nativeCreate(3, arrayOf<Any>(raw))
            assertEquals("deploy '$raw' must parse", RimeMessage.DeployMessage(state), msg)
        }
    }

    @Test
    fun unknownDeployStateDegradesToUnknown() {
        val msg = RimeMessage.nativeCreate(3, arrayOf<Any>("bogus"))
        assertTrue(msg is RimeMessage.UnknownMessage)
    }

    @Test
    fun deployStateIsCaseSensitive() {
        // valueOf 只认首字母大写的枚举名；全大写不是合法状态串
        val msg = RimeMessage.nativeCreate(3, arrayOf<Any>("SUCCESS"))
        assertTrue(msg is RimeMessage.UnknownMessage)
    }

    @Test
    fun malformedDeployParamsDegradeToUnknown() {
        assertTrue(RimeMessage.nativeCreate(3, arrayOf<Any>()) is RimeMessage.UnknownMessage)
        assertTrue(RimeMessage.nativeCreate(3, arrayOf<Any>(42)) is RimeMessage.UnknownMessage)
    }

    @Test
    fun outOfRangeTypeDegradesToUnknownAndKeepsParams() {
        val params = arrayOf<Any>("x")
        for (type in listOf(-1, 99)) {
            val msg = RimeMessage.nativeCreate(type, params)
            assertTrue("type $type must degrade", msg is RimeMessage.UnknownMessage)
            assertTrue((msg as RimeMessage.UnknownMessage).params.contentEquals(params))
        }
    }

    @Test
    fun validSchemaAndOptionStillParse() {
        val schema = RimeMessage.nativeCreate(1, arrayOf<Any>("wanxiang/Wanxiang"))
        assertEquals(
            RimeMessage.SchemaMessage(SchemaItem(id = "wanxiang", name = "Wanxiang")),
            schema,
        )
        val option = RimeMessage.nativeCreate(2, arrayOf<Any>("!ascii_mode"))
        assertEquals(RimeMessage.OptionMessage("ascii_mode", false), option)
        val optionOn = RimeMessage.nativeCreate(2, arrayOf<Any>("ascii_mode"))
        assertEquals(RimeMessage.OptionMessage("ascii_mode", true), optionOn)
    }

    @Test
    fun malformedSchemaDegradesToUnknown() {
        assertTrue(RimeMessage.nativeCreate(1, arrayOf<Any>()) is RimeMessage.UnknownMessage)
        assertTrue(RimeMessage.nativeCreate(1, arrayOf<Any>(42)) is RimeMessage.UnknownMessage)
    }
}
