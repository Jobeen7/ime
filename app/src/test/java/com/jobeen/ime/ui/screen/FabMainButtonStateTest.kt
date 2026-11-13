// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * S-L6：FAB 最终态的状态级单测——星芒坐标、600ms tween、点按转一圈、
 * 运行态行为。Compose 主机无截图手段，钉的是 MainScreen 内联逻辑
 * 抽出的纯函数（[nextFabMainState]／[fabAsteriskArmEnd]）与定稿常量；
 * 星芒与旋转手感仍须真机验收。
 *
 * 定稿（2026-11-12）：主钮 56dp 实心蓝绿 #54A0B8、米白 #DBD9C5 八臂
 * 星芒（32dp 画布、dotR 12.2dp、圆点半径 2.3dp、臂宽 2.5dp，每臂
 * 45°），平时静止，点按转一圈（600ms tween）。
 */
class FabMainButtonStateTest {

    private val dotR = 12.2f
    private val eps = 1e-3f

    @Test
    fun spinConstantsMatchSpec() {
        assertEquals(360f, FAB_SPIN_STEP_DEGREES, 0f)
        assertEquals(600, FAB_SPIN_DURATION_MS)
        assertEquals(8, FAB_ASTERISK_ARM_COUNT)
    }

    @Test
    fun clickWhenIdleSpinsOneTurnAndToggles() {
        val first = nextFabMainState(spinDegrees = 0f, expanded = false, anyRunning = false)
        assertEquals(360f, first.spinDegrees, 0f)
        assertTrue(first.expanded)
        val second = nextFabMainState(first.spinDegrees, first.expanded, anyRunning = false)
        assertEquals(720f, second.spinDegrees, 0f)
        assertEquals(false, second.expanded)
    }

    @Test
    fun clickWhenRunningStillSpinsButDoesNotToggle() {
        // 运行态下旋转照常累加（Canvas 已被进度弧取代，转动等运行结束
        // 才兑现一次），展开态不动——不重复展开、不收起。
        val running = nextFabMainState(spinDegrees = 0f, expanded = false, anyRunning = true)
        assertEquals(360f, running.spinDegrees, 0f)
        assertEquals(false, running.expanded)
        val runningExpanded =
            nextFabMainState(spinDegrees = 360f, expanded = true, anyRunning = true)
        assertEquals(720f, runningExpanded.spinDegrees, 0f)
        assertEquals(true, runningExpanded.expanded)
    }

    @Test
    fun clickAfterRunningResumesToggling() {
        // 运行结束后的下一次点按：累计角度不丢，展开正常切换。
        val after = nextFabMainState(spinDegrees = 720f, expanded = false, anyRunning = false)
        assertEquals(1080f, after.spinDegrees, 0f)
        assertTrue(after.expanded)
    }

    @Test
    fun asteriskAnglesAreEightArmsEvery45DegreesFromTop() {
        val expected = listOf(-90.0, -45.0, 0.0, 45.0, 90.0, 135.0, 180.0, 225.0)
        for (i in 0 until FAB_ASTERISK_ARM_COUNT) {
            assertEquals("arm=$i", expected[i], fabAsteriskArmAngleDegrees(i), 0.0)
        }
    }

    @Test
    fun asteriskCardinalArmsPointUpRightDownLeft() {
        // 首臂朝上（-90°），顺时针每 45° 一臂；基点须精确落在坐标轴上。
        val (upX, upY) = fabAsteriskArmEnd(0, dotR)
        assertEquals(0f, upX, eps)
        assertEquals(-dotR, upY, eps)
        val (rightX, rightY) = fabAsteriskArmEnd(2, dotR)
        assertEquals(dotR, rightX, eps)
        assertEquals(0f, rightY, eps)
        val (downX, downY) = fabAsteriskArmEnd(4, dotR)
        assertEquals(0f, downX, eps)
        assertEquals(dotR, downY, eps)
        val (leftX, leftY) = fabAsteriskArmEnd(6, dotR)
        assertEquals(-dotR, leftX, eps)
        assertEquals(0f, leftY, eps)
    }

    @Test
    fun asteriskAllEightEndsAreDistinctAndOnCircle() {
        val ends = (0 until FAB_ASTERISK_ARM_COUNT).map { fabAsteriskArmEnd(it, dotR) }
        assertEquals(FAB_ASTERISK_ARM_COUNT, ends.toSet().size)
        for ((x, y) in ends) {
            assertEquals("end=($x,$y)", dotR, sqrt(x * x + y * y), eps)
        }
    }
}
