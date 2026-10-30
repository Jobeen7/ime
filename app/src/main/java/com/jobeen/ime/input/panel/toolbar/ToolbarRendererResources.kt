package com.jobeen.ime.input.panel.toolbar

import android.graphics.drawable.Drawable

/**
 * [ToolbarRenderer] 所需的图标资源集合。
 * 将这些 Drawable 收敛到一个资源类，避免 [ToolbarRenderer] 构造函数传递大量分散参数。
 */
data class ToolbarRendererResources(
    val menu: Drawable?,
    val arrow: Drawable?,
    val clipboard: Drawable?,
    val expand: Drawable?,
    val clear: Drawable?,
    val emoji: Drawable?,
    val selectAll: Drawable?,
    val copy: Drawable?,
    val paste: Drawable?,
    val search: Drawable?,
    // 工具栏中央区自定义的候选池图标（ToolbarConfig.POOL 中其余动作）；
    // 默认 null 仅为兼容未传参的构造点，正式构造点应全部传齐，
    // 缺图标的按钮绘制为空白但动作仍可触发。
    val symbol: Drawable? = null,
    val phrases: Drawable? = null,
    val voice: Drawable? = null,
    val cursor: Drawable? = null,
    val undo: Drawable? = null,
    val redo: Drawable? = null,
    val palette: Drawable? = null,
)
