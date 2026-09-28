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
)
