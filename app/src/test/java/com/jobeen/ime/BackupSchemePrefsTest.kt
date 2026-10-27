// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份还原的多目录偏好过滤：scheme_dirs 整表不还原；分目录键是现行模型、
 * 还原时写回（旧分类称其为残留是错的）；全局键保留还原。
 */
class BackupSchemePrefsTest {

    @Test
    fun legacyPref_schemeDirsAlwaysTrue() {
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", "enabled_schema_ids_foo"))
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", ""))
    }

    /**
     * 分目录键是现行模型（还原写回），不是旧残留：只有 scheme_dirs
     * 整表才算 legacy。旧方法曾断言分键为 legacy，与已核实的现行
     * 模型矛盾，已按 spec 纠正（非按新代码改期望）。
     */
    @Test
    fun perDirKey_currentModel_notLegacy() {
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_foo"))
        assertFalse(BackupManager.isLegacyMultiDirPref("other_file", "enabled_schema_ids_x"))
    }

    /**
     * 整表跳过门（applyPayload 循环开头、创建 editor 之前判断）：
     * scheme_dirs 现行状态文件整表不碰——还原与回滚都不清空它，
     * current_dir / migrated_to_dirs 原样保留；设备本地表同样整表
     * 不碰；其余文件正常走先清后写还原。
     */
    @Test
    fun skippedPrefsFile_schemeDirsTrue() {
        assertTrue(BackupManager.isSkippedPrefsFile("scheme_dirs"))
    }

    @Test
    fun skippedPrefsFile_deviceLocalTrue() {
        assertTrue(BackupManager.isSkippedPrefsFile("speech_model_prefs"))
        assertTrue(BackupManager.isSkippedPrefsFile("wanxiang_update"))
        assertTrue(BackupManager.isSkippedPrefsFile("db_maintenance"))
        assertTrue(BackupManager.isSkippedPrefsFile("user_dict_sync"))
    }

    @Test
    fun skippedPrefsFile_ordinaryFalse() {
        assertFalse(BackupManager.isSkippedPrefsFile("schema_settings"))
        assertFalse(BackupManager.isSkippedPrefsFile("other_file"))
        assertFalse(BackupManager.isSkippedPrefsFile("trime"))
    }

    @Test
    fun legacyPref_globalAndEmptySuffixFalse() {
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids"))
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_"))
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "other_key"))
    }
}
