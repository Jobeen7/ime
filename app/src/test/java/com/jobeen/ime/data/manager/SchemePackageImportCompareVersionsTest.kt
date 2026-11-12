// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SchemePackageImport.compareVersions 的预发布语义（与 base 侧 B-L4
 * 新口径一致）：每段取前导数字比，数字相等时有后缀（预发布）者小于
 * 无后缀的正式版，后缀之间按字典序。纯数字版本号的比较结果与旧口径
 * 一致（见 SchemePackageImportTest.compareVersions_mirrorsWanxiangUpdateManager，
 * 其 LTS < 18.1.7 断言在新口径下同样成立，不另改）。
 */
class SchemePackageImportCompareVersionsTest {

    @Test
    fun rcLessThanRelease() {
        assertTrue(SchemePackageImport.compareVersions("18.1.12-rc1", "18.1.12") < 0)
        assertTrue(SchemePackageImport.compareVersions("18.1.12", "18.1.12-rc1") > 0)
    }

    @Test
    fun betaLessThanRelease() {
        assertTrue(SchemePackageImport.compareVersions("18.1.12-beta", "18.1.12") < 0)
        assertTrue(SchemePackageImport.compareVersions("1.1.18-beta", "1.1.18") < 0)
    }

    @Test
    fun prereleaseSuffixesCompareLexicographically() {
        assertTrue(SchemePackageImport.compareVersions("18.1.12-rc1", "18.1.12-rc2") < 0)
        assertTrue(SchemePackageImport.compareVersions("18.1.12-rc2", "18.1.12-rc1") > 0)
        assertTrue(SchemePackageImport.compareVersions("18.1.12-alpha", "18.1.12-beta") < 0)
    }

    @Test
    fun samePrereleaseIsEqual() {
        assertEquals(
            0,
            SchemePackageImport.compareVersions("18.1.12-rc1", "18.1.12-rc1"),
        )
    }

    @Test
    fun vPrefixStrippedBeforeSuffixComparison() {
        assertTrue(SchemePackageImport.compareVersions("v18.1.12-rc1", "18.1.12") < 0)
        assertTrue(SchemePackageImport.compareVersions("V18.1.12-rc1", "v18.1.12") < 0)
    }

    @Test
    fun pureNumericComparisonUnchanged() {
        assertTrue(SchemePackageImport.compareVersions("18.1.7", "18.1.12") < 0)
        assertTrue(SchemePackageImport.compareVersions("18.1.12", "18.1.7") > 0)
        assertEquals(0, SchemePackageImport.compareVersions("18.1.12", "18.1.12"))
        assertEquals(0, SchemePackageImport.compareVersions("v18.1.12", "18.1.12"))
    }
}
