// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.skin.model.FloatingKeyboardGeometry

/**
 * 悬浮键盘窗口（WO52 1.7）：纯决策＋几何，不碰 Android 视图。
 *
 * 口径：
 * - 开关动作 #floatingKeyboardWindowStateToggle：读键取反写回；
 *   开→浮动模式，关→恢复停靠；硬件键盘在用时不可开（Jime 无外接
 *   键盘支持，该分支恒不可达，但代码保留判断，提示文案逐字按单子）。
 * - 位置/尺寸持久化：皮肤键 floatingKeyboardWindowPortrait／
 *   floatingKeyboardWindowLandscape 按横竖屏恢复窗口几何；拖动
 *   结束保存；存宽比与中心 x。
 * - 横屏浮动时按 smallestScreenWidthDp×density 设浮动键盘宽度，
 *   竖屏走几何宽比（null = 不用该规则）。
 */
object FloatingKeyboardWindow {

    /** 硬件键盘在用时不可开的提示（单子原文，逐字）。 */
    const val HARDWARE_KEYBOARD_UNAVAILABLE_MESSAGE =
        "Floating keyboard window is unavailable while a hardware keyboard is in use"

    /** 开关翻转结果。 */
    sealed interface ToggleOutcome {
        /** 翻转为开（浮动模式）。 */
        data object Enabled : ToggleOutcome

        /** 翻转为关（恢复停靠）。 */
        data object Disabled : ToggleOutcome

        /** 硬件键盘在用，不可开（状态不变）。 */
        data object HardwareKeyboardUnavailable : ToggleOutcome
    }

    /**
     * 硬件键盘是否在用（纯判定）：hasHardwareKeyboard = 有实体键盘
     * （Configuration.KEYBOARD_QWERTY）；hardKeyboardHidden = 实体
     * 键盘已收起（Configuration.HARDKEYBOARDHIDDEN_YES）。
     */
    fun isHardwareKeyboardInUse(hasHardwareKeyboard: Boolean, hardKeyboardHidden: Boolean): Boolean =
        hasHardwareKeyboard && !hardKeyboardHidden

    /** 开关翻转：硬件键盘在用时拒绝开，其余取反。 */
    fun toggle(currentlyEnabled: Boolean, hardwareKeyboardInUse: Boolean): ToggleOutcome {
        if (hardwareKeyboardInUse && !currentlyEnabled) {
            return ToggleOutcome.HardwareKeyboardUnavailable
        }
        return if (currentlyEnabled) ToggleOutcome.Disabled else ToggleOutcome.Enabled
    }

    /** 按横竖屏取几何（缺省几何 = 宽比 0.9、中心 0.5）。 */
    fun geometryFor(
        isLandscape: Boolean,
        portrait: FloatingKeyboardGeometry?,
        landscape: FloatingKeyboardGeometry?,
    ): FloatingKeyboardGeometry =
        (if (isLandscape) landscape else portrait) ?: FloatingKeyboardGeometry()

    /**
     * 横屏浮动键盘宽度（px）：smallestScreenWidthDp×density。
     * 竖屏不走该规则（返回 null，调用方走几何宽比）。
     */
    fun landscapeWidthPx(
        isLandscape: Boolean,
        smallestScreenWidthDp: Float,
        density: Float,
    ): Int? {
        if (!isLandscape) return null
        return (smallestScreenWidthDp * density).toInt().coerceAtLeast(1)
    }

    /** 竖屏浮动键盘宽度（px）：几何宽比 × 屏幕宽。 */
    fun portraitWidthPx(screenWidthPx: Int, geometry: FloatingKeyboardGeometry): Int =
        (screenWidthPx * geometry.widthRatio).toInt()
            .coerceIn(1, screenWidthPx.coerceAtLeast(1))

    /**
     * 浮动内容左缘（px）：中心 x × 屏宽 − 宽／2，钳在屏内
     * （0..屏宽−浮动宽）。
     */
    fun resolveLeftPx(screenWidthPx: Int, floatingWidthPx: Int, geometry: FloatingKeyboardGeometry): Int {
        val maxLeft = (screenWidthPx - floatingWidthPx).coerceAtLeast(0)
        return ((geometry.centerX * screenWidthPx) - floatingWidthPx / 2f)
            .toInt().coerceIn(0, maxLeft)
    }

    /** 拖动后的中心 x：起点中心 x ＋ 横向位移／屏宽，钳在 0..1。 */
    fun dragCenterX(startCenterX: Float, dxPx: Float, screenWidthPx: Int): Float {
        if (screenWidthPx <= 0) return startCenterX.coerceIn(0f, 1f)
        return (startCenterX + dxPx / screenWidthPx).coerceIn(0f, 1f)
    }
}

/**
 * 悬浮键盘偏好（WO52 1.7）：运行期翻转与拖动几何的持久化。
 * enabled_override 缺省 = 跟随皮肤键 floatingKeyboardWindowEnabled；
 * 几何缺省 = 对应方向的皮肤键几何。写回只写偏好，不改皮肤文件。
 */
object FloatingKeyboardPrefs {
    private const val PREFS_NAME = "floating_keyboard"
    private const val KEY_ENABLED_OVERRIDE = "enabled_override"
    private const val KEY_PORTRAIT_WIDTH_RATIO = "portrait_width_ratio"
    private const val KEY_PORTRAIT_CENTER_X = "portrait_center_x"
    private const val KEY_LANDSCAPE_WIDTH_RATIO = "landscape_width_ratio"
    private const val KEY_LANDSCAPE_CENTER_X = "landscape_center_x"

    /** 生效开关值：偏好覆写优先，未覆写时取皮肤键。 */
    fun effectiveEnabled(context: Context, skinEnabled: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ENABLED_OVERRIDE)) {
            prefs.getBoolean(KEY_ENABLED_OVERRIDE, skinEnabled)
        } else {
            skinEnabled
        }
    }

    /** 写回开关覆写（# 动作与设置页开关共用）。 */
    fun setEnabledOverride(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED_OVERRIDE, enabled)
        }
    }

    /** 生效几何：偏好值优先（拖动保存后），未保存时取皮肤键。 */
    fun geometry(
        context: Context,
        isLandscape: Boolean,
        skinGeometry: FloatingKeyboardGeometry,
    ): FloatingKeyboardGeometry {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ratioKey = if (isLandscape) KEY_LANDSCAPE_WIDTH_RATIO else KEY_PORTRAIT_WIDTH_RATIO
        val centerKey = if (isLandscape) KEY_LANDSCAPE_CENTER_X else KEY_PORTRAIT_CENTER_X
        if (!prefs.contains(ratioKey) && !prefs.contains(centerKey)) return skinGeometry
        return FloatingKeyboardGeometry(
            widthRatio = prefs.getFloat(ratioKey, skinGeometry.widthRatio),
            centerX = prefs.getFloat(centerKey, skinGeometry.centerX),
        ).clamped()
    }

    /** 拖动结束保存几何（存宽比与中心 x，按方向分存）。 */
    fun saveGeometry(context: Context, isLandscape: Boolean, geometry: FloatingKeyboardGeometry) {
        val clamped = geometry.clamped()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (isLandscape) {
                putFloat(KEY_LANDSCAPE_WIDTH_RATIO, clamped.widthRatio)
                putFloat(KEY_LANDSCAPE_CENTER_X, clamped.centerX)
            } else {
                putFloat(KEY_PORTRAIT_WIDTH_RATIO, clamped.widthRatio)
                putFloat(KEY_PORTRAIT_CENTER_X, clamped.centerX)
            }
        }
    }
}
