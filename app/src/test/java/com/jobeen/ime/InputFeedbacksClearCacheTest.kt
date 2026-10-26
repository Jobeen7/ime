// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.jobeen.ime.base.feedback.InputFeedbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缺陷 10：重置清空偏好时 SharedPreferences 以 key == null 回调监听器，
 * 反馈缓存必须回到默认值，而不是在服务存活期间继续用旧值。
 *
 * 约束：单元测试只有 JUnit，无 Robolectric/Mockito 且不许加依赖，因此
 * 用内存版 SharedPreferences + ContextWrapper 子类，反射装配 InputFeedbacks
 * 的真实监听器与静态缓存，直接走 fake 偏好的 clear()/单键 commit()，
 * 模拟 Android 的回调语义（clear → 单次 null 回调；单键写 → 该键回调）。
 */
class InputFeedbacksClearCacheTest {

    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

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
        ) {
            listeners += listener
        }
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners -= listener
        }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: Set<String>?) = apply { pending[k] = v }
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
            override fun remove(k: String) = apply { pending[k] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                val changedKeys = pending.keys.toList()
                if (clearAll) values.clear()
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                // Android 语义：clear() 只发一次 null 回调；单键写发该键回调。
                if (clearAll) {
                    listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakePrefs, null) }
                } else {
                    changedKeys.forEach { key ->
                        listeners.toList().forEach {
                            it.onSharedPreferenceChanged(this@FakePrefs, key)
                        }
                    }
                }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    // ContextWrapper 为具体类：只覆盖刷新路径用到的三个方法即可。
    // contentResolver 未覆盖（基 Context 为 null 时取用抛空指针），但
    // readSystemHapticEnabled 自带 try/catch，会兜住并返回默认值 true。
    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getSystemService(name: String): Any? = null
    }

    // Kotlin 把仅 companion 内部访问的私有属性编成外部类的 private static
    // 字段（javap 实证），故反射目标是 InputFeedbacks 类本身、静态读写。
    private fun companionField(name: String) =
        InputFeedbacks::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setCached(vibration: Boolean?, followSystem: Boolean?, amplitude: Int?, sound: Boolean?) {
        companionField("cachedVibrationEnabled").set(null, vibration)
        companionField("cachedFollowSystem").set(null, followSystem)
        companionField("cachedAmplitude").set(null, amplitude)
        companionField("cachedSoundEnabled").set(null, sound)
    }

    private fun getCached(name: String): Any? =
        companionField(name).get(null)

    private fun installListener(prefs: FakePrefs, context: FakeContext) {
        companionField("appContextRef").set(null, context)
        companionField("feedbackPrefs").set(null, prefs)
        val listener = companionField("feedbackPrefsListener").get(null)
            as SharedPreferences.OnSharedPreferenceChangeListener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    @After
    fun tearDown() {
        setCached(null, null, null, null)
        companionField("appContextRef").set(null, null)
        companionField("feedbackPrefs").set(null, null)
        companionField("hapticObserver").set(null, null)
        companionField("cachedSystemHaptic").set(null, null)
        companionField("cachedVibrator").set(null, null)
    }

    @Test
    fun clearCallback_nullKey_refreshesCacheToDefaults() {
        val prefs = FakePrefs()
        // 重置前用户改过反馈：盘上是非默认值，缓存也是旧值。
        prefs.edit()
            .putBoolean("keyboard.feedback.vibration", false)
            .putBoolean("keyboard.feedback.vibration_follow_system", false)
            .putInt("keyboard.feedback.vibration_amplitude", 50)
            .putBoolean("keyboard.feedback.sound", false)
            .commit()
        val context = FakeContext(prefs)
        installListener(prefs, context)
        setCached(false, false, 50, false)

        // 模拟 AppReset 的 .edit().clear().commit()：盘上回到默认。
        prefs.edit().clear().commit()

        assertEquals(true, getCached("cachedVibrationEnabled"))
        assertEquals(true, getCached("cachedFollowSystem"))
        assertEquals(100, getCached("cachedAmplitude"))
        assertEquals(true, getCached("cachedSoundEnabled"))
    }

    @Test
    fun singleKeyCallback_feedbackKeyRefreshesAndUnrelatedKeyIgnored() {
        val prefs = FakePrefs()
        val context = FakeContext(prefs)
        installListener(prefs, context)
        setCached(true, true, 100, true)

        // 正常单键改动：反馈键回调刷新该项。
        prefs.edit().putBoolean("keyboard.feedback.sound", false).commit()
        assertEquals(false, getCached("cachedSoundEnabled"))

        // 非反馈域单键改动：回调被忽略，缓存保持原样（不被误刷新）。
        setCached(false, true, 100, false)
        prefs.edit().putBoolean("keyboard.theme.dark", true).commit()
        assertEquals(false, getCached("cachedVibrationEnabled"))
        assertEquals(false, getCached("cachedSoundEnabled"))
    }

    @Test
    fun clearCallback_withoutCacheEstablished_isNoop() {
        val prefs = FakePrefs()
        // 缓存未建立：监听器无应用上下文可刷新，必须静默无操作、不崩溃。
        val listener = companionField("feedbackPrefsListener").get(null)
            as SharedPreferences.OnSharedPreferenceChangeListener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().clear().commit()
        assertNull(getCached("cachedVibrationEnabled"))
        assertNull(getCached("cachedSoundEnabled"))
    }
}
