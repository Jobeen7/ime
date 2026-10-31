// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤备份文件相对路径白名单（解析阶段防穿越）：仅 skins/ 与
 * assets/ 下的普通文件路径放行；index.json 不走此口径（它是段内
 * 独立字段，不在 files 表中）。
 */
class BackupSkinSectionTest {

    @Test
    fun validSkinRelPaths() {
        assertTrue(BackupManager.isValidSkinRelPath("skins/s1/gen-1/skin.json"))
        assertTrue(BackupManager.isValidSkinRelPath("skins/s1/gen-1/manifest.json"))
        assertTrue(BackupManager.isValidSkinRelPath("assets/abc123.webp"))
        assertTrue(BackupManager.isValidSkinRelPath("assets/sub/deep/file.png"))
    }

    @Test
    fun invalidSkinRelPaths() {
        // 非 skins/assets 根
        assertFalse(BackupManager.isValidSkinRelPath("index.json"))
        assertFalse(BackupManager.isValidSkinRelPath("layouts/l1/layout.json"))
        assertFalse(BackupManager.isValidSkinRelPath("staging/x"))
        // 裸目录（无文件段）与空串
        assertFalse(BackupManager.isValidSkinRelPath("skins"))
        assertFalse(BackupManager.isValidSkinRelPath(""))
        // 穿越与绝对路径
        assertFalse(BackupManager.isValidSkinRelPath("skins/../assets/x"))
        assertFalse(BackupManager.isValidSkinRelPath("skins/./x"))
        assertFalse(BackupManager.isValidSkinRelPath("skins//x"))
        assertFalse(BackupManager.isValidSkinRelPath("skins/x/"))
        assertFalse(BackupManager.isValidSkinRelPath("/etc/passwd"))
        assertFalse(BackupManager.isValidSkinRelPath("skins\\x"))
    }
}
