// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO52 5.4 面板锁定：偏好默认 false＋读写回路；操作后去留决策；
 * 搜索框聚焦分支（锁定保持／未锁定收起）。
 *
 * 内存版 SharedPreferences + ContextWrapper（复用
 * InputFeedbacksClearCacheTest 的已证模式，无 Robolectric/Mockito）。
 */
class ClipboardLockTest {

    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? Set<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long =
            values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: Set<String>?) = apply { pending[k] = v }
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
            override fun remove(k: String) = apply { pending[k] = null }
            override fun clear() = apply { pending.clear() }
            override fun commit(): Boolean {
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun lockPrefDefaultsFalseAndRoundTrips() {
        val context = FakeContext(FakePrefs())
        assertFalse(ClipboardManager.isViewLocked(context))
        ClipboardManager.setViewLocked(context, true)
        assertTrue(ClipboardManager.isViewLocked(context))
        ClipboardManager.setViewLocked(context, false)
        assertFalse(ClipboardManager.isViewLocked(context))
    }

    @Test
    fun lockedNeverReturns() {
        // 锁定 → 刷新列表、面板停留（return_after_paste 开也一样）
        assertFalse(ClipboardLockPolicy.shouldReturnToKeyboard(true, true))
        assertFalse(ClipboardLockPolicy.shouldReturnToKeyboard(true, false))
    }

    @Test
    fun unlockedFollowsReturnAfterPaste() {
        assertTrue(ClipboardLockPolicy.shouldReturnToKeyboard(false, true))
        assertFalse(ClipboardLockPolicy.shouldReturnToKeyboard(false, false))
    }

    @Test
    fun searchKeptOnlyWhenLocked() {
        // 锁定且搜索视图存在 → 搜索框保持聚焦（搜索态不收起）；
        // 未锁定 → 收起搜索态（随面板关闭）。
        assertTrue(ClipboardLockPolicy.shouldKeepSearch(true))
        assertFalse(ClipboardLockPolicy.shouldKeepSearch(false))
    }
}
