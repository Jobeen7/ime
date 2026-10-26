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
