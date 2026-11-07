// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.skin.library.SkinColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已删内置主题迁移（WO6 第 3 项：绿主题无迁移，.23 审计问题 3）：
 * 从 .21 偏好种子升级——三个槽位分别存绿 ID 时，迁移后存值、实际颜色
 * 与设置页当前卡一致；幂等；跟随系统模式与混合槽位一并覆盖。
 * 决策是纯函数，主机单测覆盖；偏好读写只在 migrateRemovedBuiltin 里。
 * 读入口端到端（内存 FakePrefs/FakeContext，复用
 * InputFeedbacksClearCacheTest 的已证模式）断言写回后的存值。
 */
class KeyboardManagerMigrationTest {

    private val greenLight = "wechat-green-light"
    private val greenDark = "wechat-green-dark"
    private val light = KeyboardTheme.LIGHT_DEFAULT_ID // "light"
    private val dark = KeyboardTheme.DARK_DEFAULT_ID // "amoled"

    private fun migrate(themeId: String, lightId: String, darkId: String) =
        KeyboardManager.Keyboard.resolveRemovedBuiltinMigration(themeId, lightId, darkId)

    @Test
    fun greenLightMainThemeResetsToDarkDefaultWithFollowOn() {
        val result = migrate(greenLight, light, dark)!!
        assertEquals(dark, result.themeId)
        assertEquals(light, result.lightThemeId)
        assertEquals(dark, result.darkThemeId)
        assertEquals(true, result.followSystem)
    }

    @Test
    fun greenDarkMainThemeResetsToDarkDefaultWithFollowOn() {
        val result = migrate(greenDark, light, dark)!!
        assertEquals(dark, result.themeId)
        assertEquals(light, result.lightThemeId)
        assertEquals(dark, result.darkThemeId)
        assertEquals(true, result.followSystem)
    }

    @Test
    fun greenLightSlotResetsToLightDefaultWithoutTouchingMain() {
        val result = migrate(dark, greenLight, dark)!!
        assertEquals(dark, result.themeId)
        assertEquals(light, result.lightThemeId)
        assertEquals(dark, result.darkThemeId)
        assertEquals(false, result.followSystem)
    }

    @Test
    fun greenDarkSlotResetsToDarkDefaultWithoutTouchingMain() {
        val result = migrate(dark, light, greenDark)!!
        assertEquals(dark, result.themeId)
        assertEquals(light, result.lightThemeId)
        assertEquals(dark, result.darkThemeId)
        assertEquals(false, result.followSystem)
    }

    @Test
    fun allThreeGreenSlotsMigrateTogether() {
        val result = migrate(greenLight, greenLight, greenDark)!!
        assertEquals(dark, result.themeId)
        assertEquals(light, result.lightThemeId)
        assertEquals(dark, result.darkThemeId)
        assertEquals(true, result.followSystem)
    }

    @Test
    fun sunsetStillMigratesAsBefore() {
        // 落日口径回归：主主题落日 → 跟随开＋暗夜；槽位落日 → 只复位该槽。
        val main = migrate("sunset", light, dark)!!
        assertEquals(dark, main.themeId)
        assertEquals(true, main.followSystem)
        val slots = migrate(dark, "sunset", "sunset")!!
        assertEquals(dark, slots.themeId)
        assertEquals(light, slots.lightThemeId)
        assertEquals(dark, slots.darkThemeId)
        assertEquals(false, slots.followSystem)
    }

    @Test
    fun validIdsNeedNoMigration() {
        assertNull(migrate("monet", light, dark))
        assertNull(migrate(light, light, dark))
        assertNull(migrate(dark, light, dark))
        assertNull(migrate("custom_legacy", light, dark))
    }

    @Test
    fun migrationIsIdempotent() {
        val first = migrate(greenLight, greenLight, greenDark)!!
        assertNull(migrate(first.themeId, first.lightThemeId, first.darkThemeId))
        val sunsetFirst = migrate("sunset", "sunset", "sunset")!!
        assertNull(
            migrate(
                sunsetFirst.themeId,
                sunsetFirst.lightThemeId,
                sunsetFirst.darkThemeId,
            ),
        )
    }

    @Test
    fun mixedSlotsResetBySlotNotByRemovedShade() {
        // 浅槽存深绿、深槽存浅绿：替代由槽位决定，不由被删主题的深浅决定。
        val result = migrate(dark, greenDark, greenLight)!!
        assertEquals(light, result.lightThemeId)
        assertEquals(dark, result.darkThemeId)
        assertEquals(false, result.followSystem)
        val withMain = migrate(greenDark, greenDark, greenLight)!!
        assertEquals(dark, withMain.themeId)
        assertEquals(light, withMain.lightThemeId)
        assertEquals(dark, withMain.darkThemeId)
        assertEquals(true, withMain.followSystem)
    }

    @Test
    fun migratedLightSlotIsGenuineLightDefault() {
        // 缺陷对照（代码事实，见 KeyboardTheme.byId）：迁移前浅绿 ID 不在内置
        // 列表内，byId 兜底落到暗夜默认（键面白→深灰、底近透明→纯黑）。
        // 主机单测不触 KeyboardThemePresets（其初始化调 android.graphics.Color，
        // 主机桩会抛），故此处断言 ID 本身：迁移后浅槽就是浅色默认 ID。
        assertEquals("wechat-green-light", greenLight)
        val result = migrate(dark, greenLight, dark)!!
        assertEquals(light, result.lightThemeId)
        assertEquals(light, KeyboardTheme.LIGHT_DEFAULT_ID)
        assertEquals(dark, result.darkThemeId)
        assertEquals(dark, KeyboardTheme.DARK_DEFAULT_ID)
    }

    @Test
    fun followSystemModeFollowsSystemShadeAfterMigration() {
        val result = migrate(greenLight, greenLight, greenDark)!!
        // 跟随系统：系统浅色取浅槽、系统深色取深槽；生效 id 就是槽位默认 id
        //（与 Light/Amoled 预设的 id 一致，颜色即该预设的颜色）。
        assertEquals(
            light,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM,
                result.themeId,
                result.lightThemeId,
                result.darkThemeId,
                false,
            ),
        )
        assertEquals(
            dark,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM,
                result.themeId,
                result.lightThemeId,
                result.darkThemeId,
                true,
            ),
        )
    }

    @Test
    fun libraryCardSelectionMatchesEffectiveThemeAfterMigration() {
        // 设置页当前卡与键盘侧同口径：生效 id 由迁移后的三槽算出，
        // 且该 id 就是内置预设的 id（Light 卡 id 为 "light"，Amoled 卡为
        // "amoled"），库页按 id 选中对应卡，不再因存值是已删绿 ID 而
        // 出现“选中卡与实际显示不一致”。
        val result = migrate(greenLight, greenLight, greenDark)!!
        val effectiveLight = KeyboardColors.effectiveBuiltinThemeId(
            SkinColorMode.LIGHT,
            result.themeId,
            result.lightThemeId,
            result.darkThemeId,
            false,
        )
        val effectiveDark = KeyboardColors.effectiveBuiltinThemeId(
            SkinColorMode.DARK,
            result.themeId,
            result.lightThemeId,
            result.darkThemeId,
            true,
        )
        assertEquals(light, effectiveLight)
        assertEquals(dark, effectiveDark)
        assertEquals(KeyboardTheme.LIGHT_DEFAULT_ID, effectiveLight)
        assertEquals(KeyboardTheme.DARK_DEFAULT_ID, effectiveDark)
    }

    // ---- 端到端：经公开读入口走真实 migrateRemovedBuiltin 写回 ----

    // 内存版 SharedPreferences（JUnit-only、无 Robolectric/Mockito）。
    // 生产侧的 prefs.edit{} 走 FakeEditor.apply() → commit() 写 values，
    // 与真机 apply 语义一致（同文件写回、监听器不触发——迁移不注册监听）。
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
            override fun clear() = apply { pending.clear(); values.clear() }
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
    }

    // .21 偏好种子：三槽分别存绿 ID（主=浅绿、浅槽=浅绿、深槽=深绿），跟随关。
    private fun greenSeed(): FakePrefs {
        val prefs = FakePrefs()
        prefs.edit()
            .putString(KeyboardManager.Keyboard.KEY_THEME, greenLight)
            .putString(KeyboardManager.Keyboard.KEY_LIGHT_THEME, greenLight)
            .putString(KeyboardManager.Keyboard.KEY_DARK_THEME, greenDark)
            .putBoolean(KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, false)
            .commit()
        return prefs
    }

    @Test
    fun storedValuesMigrateThroughReadEntryPoint() {
        val prefs = greenSeed()
        val context = FakeContext(prefs)
        assertEquals(dark, KeyboardManager.Keyboard.getThemeId(context))
        assertEquals(dark, prefs.getString(KeyboardManager.Keyboard.KEY_THEME, null))
        assertEquals(light, prefs.getString(KeyboardManager.Keyboard.KEY_LIGHT_THEME, null))
        assertEquals(dark, prefs.getString(KeyboardManager.Keyboard.KEY_DARK_THEME, null))
        assertTrue(prefs.getBoolean(KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, false))
    }

    @Test
    fun slotOnlyMigrationLeavesMainAndFollowUntouched() {
        val prefs = FakePrefs()
        prefs.edit()
            .putString(KeyboardManager.Keyboard.KEY_THEME, dark)
            .putString(KeyboardManager.Keyboard.KEY_LIGHT_THEME, greenLight)
            .putString(KeyboardManager.Keyboard.KEY_DARK_THEME, greenDark)
            .putBoolean(KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, false)
            .commit()
        val context = FakeContext(prefs)
        assertEquals(light, KeyboardManager.Keyboard.getLightThemeId(context))
        assertEquals(dark, KeyboardManager.Keyboard.getDarkThemeId(context))
        assertEquals(dark, KeyboardManager.Keyboard.getThemeId(context))
        assertFalse(KeyboardManager.Keyboard.getFollowSystem(context))
        assertEquals(dark, prefs.getString(KeyboardManager.Keyboard.KEY_THEME, null))
    }

    @Test
    fun cleanValuesAreNotRewritten() {
        val prefs = FakePrefs()
        prefs.edit()
            .putString(KeyboardManager.Keyboard.KEY_THEME, dark)
            .putString(KeyboardManager.Keyboard.KEY_LIGHT_THEME, light)
            .putString(KeyboardManager.Keyboard.KEY_DARK_THEME, dark)
            .putBoolean(KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, false)
            .commit()
        val before = prefs.values.toMap()
        val context = FakeContext(prefs)
        KeyboardManager.Keyboard.getThemeId(context)
        KeyboardManager.Keyboard.getLightThemeId(context)
        KeyboardManager.Keyboard.getDarkThemeId(context)
        KeyboardManager.Keyboard.getFollowSystem(context)
        assertEquals(before, prefs.values.toMap())
    }

    @Test
    fun followSystemModeReadsMigratedSlotsEndToEnd() {
        val prefs = greenSeed()
        val context = FakeContext(prefs)
        assertTrue(KeyboardManager.Keyboard.getFollowSystem(context))
        val themeId = KeyboardManager.Keyboard.getThemeId(context)
        val lightId = KeyboardManager.Keyboard.getLightThemeId(context)
        val darkId = KeyboardManager.Keyboard.getDarkThemeId(context)
        assertEquals(
            light,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM,
                themeId,
                lightId,
                darkId,
                false,
            ),
        )
        assertEquals(
            dark,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM,
                themeId,
                lightId,
                darkId,
                true,
            ),
        )
    }
}
