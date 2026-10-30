// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

/**
 * 按压态内容解析（重点候选 8-2，纯函数）：
 *
 * - 图标：按压态已配且常态图标已配 → 按压图标；否则常态图标
 *   （常态图标未配时按压字形无可恢复的文本，渲染忽略按压图标，
 *   避免抬起后文本丢失）。
 * - 图片：按压态已配 → 按压图片；否则常态图片（或空 → 颜色键面）。
 *   图片层叠加显示、抬起隐藏，恢复天然干净，故不要求常态图片已配。
 */
object PressedContent {

    fun effectiveIcon(
        normalIcon: String?,
        pressedIcon: String?,
        isPressed: Boolean,
    ): String? {
        if (!isPressed) return normalIcon
        if (pressedIcon == null || normalIcon == null) return normalIcon
        return pressedIcon
    }

    fun effectiveImageAssetId(
        normalImageAssetId: String?,
        pressedImageAssetId: String?,
        isPressed: Boolean,
    ): String? {
        if (!isPressed) return normalImageAssetId
        return pressedImageAssetId ?: normalImageAssetId
    }
}
