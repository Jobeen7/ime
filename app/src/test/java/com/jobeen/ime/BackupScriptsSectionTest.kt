// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤脚本备份文件相对路径白名单（解析阶段防穿越）：仅
 * <id>/script.js 与 <id>/meta.json 两段路径放行；id 须过脚本
 * id 口径；其余一律拒绝。
 */
class BackupScriptsSectionTest {

    @Test
    fun validScriptRelPaths() {
        assertTrue(BackupManager.isValidSkinScriptRelPath("abc123/script.js"))
        assertTrue(BackupManager.isValidSkinScriptRelPath("abc123/meta.json"))
        assertTrue(BackupManager.isValidSkinScriptRelPath("a.b-c_d/script.js"))
    }

    @Test
    fun invalidScriptRelPaths() {
        // 非脚本文件名（半写 .tmp 与其它文件不进包）
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123/script.js.tmp"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123/meta.json.tmp"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123/other.txt"))
        // 裸目录、单段、更深嵌套与空串
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123/sub/script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath(""))
        // 穿越与绝对路径
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123/../other/script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123/./script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123//script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("/etc/passwd"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("abc123\\script.js"))
        // 非法 id（分隔符、. / ..）
        assertFalse(BackupManager.isValidSkinScriptRelPath("../script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("./script.js"))
        assertFalse(BackupManager.isValidSkinScriptRelPath("a/b/script.js"))
    }
}
