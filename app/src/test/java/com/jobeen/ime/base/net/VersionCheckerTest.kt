package com.jobeen.ime.base.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-L4：版本比较把非数字段当 0 的旧口径——预发布须小于正式版。 */
class VersionCheckerTest {

    @Test
    fun prerelease_isLessThanRelease() {
        assertTrue(VersionChecker.compareVersions("1.1.18-beta", "1.1.18") < 0)
        assertTrue(VersionChecker.compareVersions("1.1.18", "1.1.18-beta") > 0)
        assertTrue(VersionChecker.compareVersions("1.1.18-rc1", "1.1.18-rc2") < 0)
        assertTrue(VersionChecker.compareVersions("1.1.18-rc1", "1.1.18") < 0)
    }

    @Test
    fun plainNumeric_unchangedSemantics() {
        assertEquals(0, VersionChecker.compareVersions("1.1.18", "1.1.18"))
        assertTrue(VersionChecker.compareVersions("1.1.19", "1.1.18") > 0)
        assertTrue(VersionChecker.compareVersions("1.1.18", "1.1.19") < 0)
        assertTrue(VersionChecker.compareVersions("1.1", "1.1.0") == 0)
        assertTrue(VersionChecker.compareVersions("1.10", "1.9") > 0)
    }

    @Test
    fun nonNumericSegment_notTreatedAsZero() {
        // 旧口径 "LTS" 整段当 0；新口径按前导数字＋后缀比，
        // 非数字版本仍小于数字版本，但不再与 0.0.0 判相等
        assertTrue(VersionChecker.compareVersions("LTS", "18.1.7") < 0)
        assertTrue(VersionChecker.compareVersions("1.1.18-alpha", "1.1.18-beta") < 0)
    }

    @Test
    fun callTimeout_isBounded() {
        // B-L10：HttpUtil client 须有总时长上限
        assertEquals(120_000L, HttpUtil.clientCallTimeoutMs)
    }
}
