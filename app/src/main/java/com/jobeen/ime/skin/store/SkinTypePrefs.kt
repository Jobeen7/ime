package com.jobeen.ime.skin.store

import android.content.Context
import androidx.core.content.edit

/**
 * 按皮肤隔离的键盘类型持久化（多主槽一期）。
 *
 * - 只记一对（皮肤 id，类型）：换肤即复位——读取时存过皮肤
 *   与当前皮肤不一致即视为无存过类型（见 [resolveStoredType]），
 *   不做跨皮肤迁移、不保留旧皮肤的类型历史。
 * - 初始类型不由此处决定：无存过类型时调用方按输入模式取
 *   初始类型（中文 pinyin、英文 alphabetic，见
 *   KeyboardTypeState.initialType），本类只负责“记住/忘掉”。
 * - 偏好文件独立于皮肤库偏好（`skin_library`）：类型是运行
 *   态恢复数据，不是库页设置，换肤复位语义与库页开关无关。
 */
object SkinTypePrefs {
    private const val PREFS_NAME = "skin_keyboard_type"
    private const val KEY_SKIN_ID = "skin_id"
    private const val KEY_TYPE = "keyboard_type"

    /**
     * 纯决策（可单测）：存过皮肤与当前皮肤一致时采纳存过类型，
     * 否则返回 null（换肤复位）；空类型亦视为无存过类型。
     */
    fun resolveStoredType(
        storedSkinId: String?,
        storedType: String?,
        currentSkinId: String?,
    ): String? {
        if (currentSkinId == null || storedSkinId != currentSkinId) return null
        return storedType?.takeIf { it.isNotBlank() }
    }

    /** 当前皮肤存过的类型；换肤/未存过/无皮肤时为 null。 */
    fun getType(context: Context, skinId: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return resolveStoredType(
            prefs.getString(KEY_SKIN_ID, null),
            prefs.getString(KEY_TYPE, null),
            skinId,
        )
    }

    /** 记下某皮肤的类型；空 id/空类型不写（不以空值冲掉有效记录）。 */
    fun setType(context: Context, skinId: String, type: String) {
        if (skinId.isBlank() || type.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_SKIN_ID, skinId)
            putString(KEY_TYPE, type)
        }
    }

    /** 清空存过的类型（皮肤删除/恢复默认时调用方清理用）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_SKIN_ID)
            remove(KEY_TYPE)
        }
    }
}
