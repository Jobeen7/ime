package com.jobeen.ime.base.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-M5（大小双关卡）／B-L10（总时长上限）。B-M4 的互斥需联网与 Context，主机单测不覆盖。 */
class AppUpdateManagerTest {

    @Test
    fun sizeCheck_bothGatesMustPass() {
        // 两道都对上才通过
        assertTrue(AppUpdateManager.sizeCheckPassed(100, 100, 100))
        // Content-Length 对不上即拒（截断包）
        assertFalse(AppUpdateManager.sizeCheckPassed(50, 100, 100))
        // expectedSize 缺失（0）时 Content-Length 仍是有效关卡
        assertFalse(AppUpdateManager.sizeCheckPassed(50, 100, 0))
        assertTrue(AppUpdateManager.sizeCheckPassed(100, 100, 0))
        // Content-Length 缺失（-1/0）时只验 expectedSize
        assertTrue(AppUpdateManager.sizeCheckPassed(100, -1, 100))
        assertFalse(AppUpdateManager.sizeCheckPassed(50, -1, 100))
        assertTrue(AppUpdateManager.sizeCheckPassed(100, 0, 100))
        // 两道都缺失时不误拒（下载仍有 SHA／安装器兜底）
        assertTrue(AppUpdateManager.sizeCheckPassed(100, 0, 0))
        // 多传同样拒
        assertFalse(AppUpdateManager.sizeCheckPassed(150, 100, 100))
    }

    @Test
    fun callTimeout_isBounded() {
        // B-L10：APK 下载 client 须有总时长上限（30 分钟）
        assertEquals(30L * 60 * 1000, AppUpdateManager.clientCallTimeoutMs)
    }
}
