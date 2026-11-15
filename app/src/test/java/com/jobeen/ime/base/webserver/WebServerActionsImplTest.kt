// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.webserver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-L-07：Wi-Fi 传皮肤落盘目标走 SkinLibraryLayout 口径。
 * applySkin 本体需 Context（无 Robolectric 依赖），只测纯函数
 * applySkinTargetDir 的路径口径。
 */
class WebServerActionsImplTest {

    @Test
    fun applySkinTargetDir_landsUnderSkinsGen1() {
        val root = File("/tmp/skin_library")
        val target = WebServerActionsImpl.applySkinTargetDir(root, "bin-white")
        assertEquals(File(root, "skins/bin-white/gen-1"), target)
    }

    @Test
    fun applySkinTargetDir_staysInsideLibraryRoot() {
        val root = File("/tmp/skin_library")
        // 名字已过 isValidSkinName（拒 "."／".."／斜杠），即使带点
        // 也落在本库内，不会越界写盘。
        val target = WebServerActionsImpl.applySkinTargetDir(root, "a..b-c_d")
        assertTrue(target.path.startsWith(File(root, "skins").path + File.separator))
    }
}
