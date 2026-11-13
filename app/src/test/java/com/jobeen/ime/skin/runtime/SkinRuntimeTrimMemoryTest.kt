// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import android.content.ComponentCallbacks2
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S-M3：内存压力降档入口 [SkinRuntime.onTrimMemory] 的 level 语义——
 * RUNNING_LOW（10）及以上置位 [SkinRuntime.trimEscalated]，以下不置位。
 * 接线本身（ImeApplication.onTrimMemory 调本函数）是 Application 层，
 * JVM 单测覆盖不到，只钉住本函数的阈值行为；消费侧
 * （SkinSnapshotFactory.evaluateTier 一次性消费后复位）由既有测试覆盖。
 */
class SkinRuntimeTrimMemoryTest {

    @Before
    fun setUp() {
        SkinRuntime.trimEscalated = false
    }

    @After
    fun tearDown() {
        // 对象是进程内单例：复位，避免污染同 JVM 的其他测试。
        SkinRuntime.trimEscalated = false
    }

    @Test
    fun runningLowSetsEscalated() {
        SkinRuntime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(SkinRuntime.trimEscalated)
    }

    @Test
    fun runningCriticalSetsEscalated() {
        SkinRuntime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertTrue(SkinRuntime.trimEscalated)
    }

    @Test
    fun uiHiddenSetsEscalated() {
        // UI_HIDDEN（20）高于 RUNNING_LOW：后台内存压力同样降档。
        SkinRuntime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertTrue(SkinRuntime.trimEscalated)
    }

    @Test
    fun runningModerateDoesNotSetEscalated() {
        SkinRuntime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertFalse(SkinRuntime.trimEscalated)
    }

    @Test
    fun zeroLevelDoesNotSetEscalated() {
        SkinRuntime.onTrimMemory(0)
        assertFalse(SkinRuntime.trimEscalated)
    }
}
