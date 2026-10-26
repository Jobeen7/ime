// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemeSwitchPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 方案来源判定（旧切换编排的存活部分）。 */
class SchemeSwitchPolicyTest {

    @Test
    fun classifySource_userDirWinsOtherwiseBuiltin() {
        assertEquals(
            SchemeSwitchPolicy.SchemeSource.USER,
            SchemeSwitchPolicy.classifySource(inUserDir = true, inSharedDir = true),
        )
        assertEquals(
            SchemeSwitchPolicy.SchemeSource.BUILTIN,
            SchemeSwitchPolicy.classifySource(inUserDir = false, inSharedDir = true),
        )
        // 两层都没有时回落内置：第一期列表只含已部署的内置方案。
        assertEquals(
            SchemeSwitchPolicy.SchemeSource.BUILTIN,
            SchemeSwitchPolicy.classifySource(inUserDir = false, inSharedDir = false),
        )
    }
}
