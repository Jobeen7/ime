// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份还原的分目录键口径（剪-O1 修后）：
 * schema_settings 里的 enabled_schema_ids_<目录> 是现行模型
 * （SchemaManager.saveEnabledIds 双写分目录键＋全局镜像键），
 * 还原时必须写回，不再当旧残留跳过。还原逻辑本体走
 * SharedPreferences、本地 JVM 单测无法跑通 applyPayload，故测
 * 其纯逻辑谓词 isLegacyMultiDirPref——applyPayload 循环内逐条
 * 调它，谓词 false 即写回，行为与谓词一致。
 * 只有 scheme_dirs 文件仍属旧状态（整表已由 isSkippedPrefsFile
 * 在 editor 创建前跳过，此处是第二道 guard）。
 */
class BackupRestoreDirKeysTest {

    @Test
    fun perDirKey_schemaSettings_restored() {
        assertFalse(
            BackupManager.isLegacyMultiDirPref(
                "schema_settings",
                "enabled_schema_ids_default",
            ),
        )
        assertFalse(
            BackupManager.isLegacyMultiDirPref(
                "schema_settings",
                "enabled_schema_ids_some_dir",
            ),
        )
    }

    @Test
    fun globalKey_schemaSettings_restored() {
        assertFalse(
            BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids"),
        )
        assertFalse(
            BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_"),
        )
    }

    @Test
    fun otherKeys_schemaSettings_restored() {
        assertFalse(
            BackupManager.isLegacyMultiDirPref("schema_settings", "grammar_model"),
        )
        assertFalse(
            BackupManager.isLegacyMultiDirPref("other_file", "enabled_schema_ids_x"),
        )
    }

    @Test
    fun schemeDirsFile_stillLegacy() {
        assertTrue(
            BackupManager.isLegacyMultiDirPref("scheme_dirs", "enabled_schema_ids_foo"),
        )
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", "current_dir"))
    }

    @Test
    fun skippedPrefsFile_unchanged() {
        assertTrue(BackupManager.isSkippedPrefsFile("scheme_dirs"))
        assertFalse(BackupManager.isSkippedPrefsFile("schema_settings"))
    }
}
