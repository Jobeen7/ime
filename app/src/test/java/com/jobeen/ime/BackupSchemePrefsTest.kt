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

    @Test
    fun legacyPref_globalAndEmptySuffixFalse() {
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids"))
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_"))
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "other_key"))
    }
}
