package com.jobeen.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 前删字素簇口径（定稿四.4 / 详设 §5.2）：BreakIterator 扩展
 * 字素簇——组合符、国旗、代理对绝不劈开。JVM 的 BreakIterator
 * 与 Android ICU 在常见簇形上口径一致；ZWJ 序列以真机为准。
 */
class ForwardDeletePlannerTest {

    private fun cluster(text: String) = ForwardDeletePlanner.firstCluster(text)

    @Test
    fun emptyTextHasNoCluster() {
        assertNull(cluster(""))
    }

    @Test
    fun asciiSingleChar() {
        assertEquals(ForwardDeletePlanner.Cluster(1, 1), cluster("abc"))
    }

    @Test
    fun cjkSingleChar() {
        assertEquals(ForwardDeletePlanner.Cluster(1, 1), cluster("中文"))
    }

    @Test
    fun surrogatePairIsOneCluster() {
        // 😀 = U+1F600：1 码点、2 个 UTF-16 单元
        assertEquals(ForwardDeletePlanner.Cluster(1, 2), cluster("😀x"))
    }

    @Test
    fun combiningMarkStaysWithBase() {
        // e + U+0301 组合锐音符：一簇、2 码点
        assertEquals(ForwardDeletePlanner.Cluster(2, 2), cluster("éx"))
    }

    @Test
    fun flagPairIsOneCluster() {
        // 🇨🇳 = 两个区域指示符：2 码点、4 个 UTF-16 单元、一簇
        assertEquals(ForwardDeletePlanner.Cluster(2, 4), cluster("🇨🇳x"))
    }

    @Test
    fun crlfIsOneCluster() {
        assertEquals(ForwardDeletePlanner.Cluster(2, 2), cluster("\r\nx"))
    }

    @Test
    fun onlyFirstClusterMeasured() {
        assertEquals(ForwardDeletePlanner.Cluster(1, 1), cluster("a🇨🇳"))
        assertEquals(ForwardDeletePlanner.Cluster(2, 4), cluster("🇨🇳ab"))
    }
}
