// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份还原的旧多目录方案残留过滤（与 .6 的 isLegacyMultiDirPref
 * 同口径）：scheme_dirs 整表不还原、分键不还原、全局键保留还原。
 */
class BackupSchemePrefsTest {

    @Test
    fun legacyPref_schemeDirsAlwaysTrue() {
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", "enabled_schema_ids_foo"))
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", ""))
    }

    @Test
    fun legacyPref_perDirKeyTrue() {
        assertTrue(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_foo"))
        assertTrue(BackupManager.isLegacyMultiDirPref("other_file", "enabled_schema_ids_x"))
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
