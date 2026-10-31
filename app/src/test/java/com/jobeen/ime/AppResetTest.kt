// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.AppReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppReset 白名单纯逻辑：只列全树实际存在的偏好文件，未知文件不动。 */
class AppResetTest {

    @Test
    fun whitelist_containsAllKnownPrefsAndRejectsUnknown() {
        // 本表须与全库 PREFS_NAME 逐项对齐（2026-10-31 grep 核查共 20 个，
        // 含 db_maintenance 与 jime_fab 这两个无 PREFS_NAME 常量项）：
        // 新增偏好文件时同步更新此表与白名单，有意豁免须注明。
        val expected = setOf(
            "keyboard_settings",
            "schema_settings",
            "scheme_dirs",
            "scheme_download",
            "scheme_import",
            "candidate_settings",
            "clipboard_settings",
            "phrase_prefs",
            "deleted_words",
            "rime_sync",
            "wanxiang_update",
            "speech_client",
            "speech_model_prefs",
            "user_dict_sync",
            "db_maintenance",
            "skin_library",
            "appearance_settings",
            "symbol_bar",
            "cloud_voice",
            "jime_fab",
        )
        assertEquals(expected, AppReset.RESET_PREFS_NAMES)
        for (name in expected) {
            assertTrue(name, AppReset.shouldClearPrefs(name))
        }
        // 未知 / 第三方库偏好文件不动。
        assertFalse(AppReset.shouldClearPrefs("unknown_future_prefs"))
        assertFalse(AppReset.shouldClearPrefs("com.example.library_prefs"))
        assertFalse(AppReset.shouldClearPrefs(""))
    }
}
