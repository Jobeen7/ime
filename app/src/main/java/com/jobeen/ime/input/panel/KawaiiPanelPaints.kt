package com.jobeen.ime.input.panel

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

class Paints(context: Context) {
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val toolbarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    val candidateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val candidateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    val candidateIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    /**
     * 候选注释画笔（2026-10-29 外观轮）：注释此前与序号共用
     * indexPaint；拆分为独立画笔后注释字体可独立选（颜色仍与
     * 序号同值，外观默认不变）。
     */
    val candidateCommentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    var toolbarPressedColor: Int = 0
    var toolbarIconColor: Int = 0
    var keyboardBackground: Int = 0
    /**
     * 工具栏按钮表面（2026-10-29 外观轮全局样式）：null＝不画
     * 按钮底（现行透明行为）；非空时按钮后画圆角底＋边框。
     * 跟随按键时取全局按键值，皮肤指定按键背景时全局值被
     * 屏蔽、按钮底不出现（默认行为不变）。
     */
    var toolbarButtonBg: Int? = null
    var toolbarButtonRadiusDp: Float = 0f
    var toolbarButtonBorderWidthDp: Float = 1f
    var toolbarButtonBorderColor: Int = 0
    val toolbarButtonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val toolbarButtonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    fun updateColors(context: Context) {
        val scheme = KeyboardColors.resolve(context)
        val panel = scheme.panel
        bgPaint.color = panel.background
        keyboardBackground = scheme.background
        dividerPaint.color = panel.candidateDivider
        toolbarTextPaint.color = panel.toolbarText
        toolbarIconColor = panel.toolbarIcon
        candidateBgPaint.color = panel.candidateBackground
        candidateTextPaint.color = panel.candidateText
        candidateIndexPaint.color = panel.candidateIndex
        toolbarPressedColor = panel.toolbarPressed
        // 工具栏按钮表面（全局样式层）：皮肤样式 presence 决定
        // 全局按键值是否生效；跟随按键时半径取生效键圆角。
        val global = GlobalStylePrefs.get(context)
        val snapshot = SkinSnapshotProvider.peek()
        val stylePresence = snapshot?.let {
            val dark = SkinColorMode.isDark(context)
            if (dark) it.darkStylePresence ?: it.stylePresence else it.stylePresence
        } ?: emptySet()
        if (global.linkToolbar) {
            toolbarButtonBg = GlobalStyleResolver.keyBackground(global, stylePresence)
            toolbarButtonRadiusDp = scheme.cornerRadius
            toolbarButtonBorderWidthDp =
                GlobalStyleResolver.clampBorderWidth(global.key.borderWidthDp)
            toolbarButtonBorderColor =
                GlobalStyleResolver.keyBorderStroke(global, stylePresence)
                    ?: scheme.keyBorderStroke
        } else {
            toolbarButtonBg = global.toolbar.background
            toolbarButtonRadiusDp = global.toolbar.cornerRadiusDp
                ?: scheme.cornerRadius
            toolbarButtonBorderWidthDp =
                GlobalStyleResolver.clampBorderWidth(global.toolbar.borderWidthDp)
            toolbarButtonBorderColor =
                global.toolbar.borderColor ?: scheme.keyBorderStroke
        }
        toolbarButtonBgPaint.color = toolbarButtonBg ?: 0
        toolbarButtonBorderPaint.color = toolbarButtonBorderColor
        toolbarButtonBorderPaint.strokeWidth =
            toolbarButtonBorderWidthDp * context.resources.displayMetrics.density
        // 字族跟随（2026-10-27 新增分流，2026-10-29 外观轮改走
        // 全局字体层）：皮肤字族指定时全部角色用皮肤字族，否则
        // 各角色独立取全局选择；无选择时各画笔恢复原有默认
        // （工具栏粗体、候选常规），不把“未指定”变成字体切换。
        toolbarTextPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.TOOLBAR, Typeface.BOLD)
                ?: Typeface.DEFAULT_BOLD
        candidateTextPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.CANDIDATE, Typeface.NORMAL)
        candidateIndexPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.INDEX, Typeface.NORMAL)
        candidateCommentPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.COMMENT, Typeface.NORMAL)
    }

    fun applyDensity(density: Float) {
        dividerPaint.strokeWidth = 1f * density
        toolbarTextPaint.textSize = 16f * density
        candidateTextPaint.textSize = 17f * density
        candidateIndexPaint.textSize = 14f * density
    }
}
