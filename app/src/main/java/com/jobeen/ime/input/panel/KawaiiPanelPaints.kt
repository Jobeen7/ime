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
    companion object {
        /** 候选主文字字号（dp）：三参考皮肤横条 candidateStyle.textFontSize 皆 18。 */
        const val CANDIDATE_TEXT_DP = 18f
        /**
         * 候选序号字号（dp，E2 对端实测值）：三参考皮肤横条
         * candidateStyle.indexFontSize 皆 18，旧 16 为误设。
         */
        const val CANDIDATE_INDEX_DP = 18f
        /**
         * 候选注释字号（dp，E2 对端实测值）：横条 candidateStyle
         * commentFontSize 在两皮肤为 11、一皮肤为 14；取多数 11
         * （首轮记录的 11–12 区间亦佐证 11；14 为单皮肤离群值）。
         * 竖格 commentFontSize（12 / 18）不走此画笔：网格只画主文字。
         */
        const val CANDIDATE_COMMENT_DP = 11f
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val toolbarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    val candidateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val candidateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    /** 选中候选文字画笔：颜色取 panel.selectedCandidateText，其余同候选画笔。 */
    val selectedCandidateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    val candidateIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    /**
     * 候选注释画笔（2026-10-29 外观轮）：注释此前与序号共用
     * indexPaint；拆分为独立画笔后注释字体可独立选（颜色仍与
     * 序号同值，外观默认不变）。
     */
    val candidateCommentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
    var toolbarPressedColor: Int = 0
    var toolbarIconColor: Int = 0
    /** 工具栏开关启用色（E6）：updateColors 从 panel.toolbarActived 同步。 */
    var toolbarActivedColor: Int = 0
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
    /**
     * 横条候选区背景（审计 B 项）：皮肤 horizontalCandidatesStyle
     * 指定时组字条用它代替面板底；null = 现行面板底。
     * 组字渲染器（ComposingRenderer）与条视图底渐变共用此值，
     * 工具栏态不用（见 KawaiiPanelView.onDraw 的渲染器守卫）。
     */
    var candidateStripBg: Int? = null
    /**
     * 候选序号／注释生效字号（审计 I 项）：updateColors 从快照
     * 同步（缺失 = E2 对端默认值），applyDensity 消费；主题刷新
     * 时 KawaiiPanelView.refreshTheme 追加 applyDensity，使字号
     * 随皮肤切换即时生效（此前 refreshTheme 只刷颜色）。
     */
    var candidateIndexSizeDp: Float = CANDIDATE_INDEX_DP
    var candidateCommentSizeDp: Float = CANDIDATE_COMMENT_DP
    /**
     * 候选按压底色（审计 I 项）：updateColors 从 panel 同步；
     * null = 未指定（渲染回落候选底）。
     */
    var candidatePressedColor: Int? = null
    /**
     * 首选候选色（审计 I 项）：updateColors 从 panel 同步；
     * null = 未指定（首选着色关闭）。
     */
    var preferredBackground: Int? = null
    var preferredText: Int? = null
    var preferredComment: Int? = null
    var preferredIndex: Int? = null

    fun updateColors(context: Context) {
        val scheme = KeyboardColors.resolve(context)
        val panel = scheme.panel
        bgPaint.color = panel.background
        keyboardBackground = scheme.background
        dividerPaint.color = panel.candidateDivider
        toolbarTextPaint.color = panel.toolbarText
        toolbarIconColor = panel.toolbarIcon
        // 开关启用色（E6）：工具栏池钮开关态着色用；菜单网格同口径。
        toolbarActivedColor = panel.toolbarActived
        candidateBgPaint.color = panel.candidateBackground
        candidateTextPaint.color = panel.candidateText
        selectedCandidateTextPaint.color = panel.selectedCandidateText
        candidateIndexPaint.color = panel.candidateIndex
        // 注释色（审计 I 项）：此前 updateColors 从未赋值，画笔恒为
        // 默认黑色；现接线为皮肤注释色，缺失回落序号色。
        candidateCommentPaint.color = panel.candidateComment ?: panel.candidateIndex
        toolbarPressedColor = panel.toolbarPressed
        candidateStripBg = SkinSnapshotProvider.peek()?.horizontalCandidatesStyle?.background
        candidateIndexSizeDp =
            SkinSnapshotProvider.peek()?.candidateIndexSizeDp ?: CANDIDATE_INDEX_DP
        candidateCommentSizeDp =
            SkinSnapshotProvider.peek()?.candidateCommentSizeDp ?: CANDIDATE_COMMENT_DP
        candidatePressedColor = panel.candidatePressed
        preferredBackground = panel.preferredBackground
        preferredText = panel.preferredText
        preferredComment = panel.preferredComment
        preferredIndex = panel.preferredIndex
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
        selectedCandidateTextPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.CANDIDATE, Typeface.NORMAL)
        candidateIndexPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.INDEX, Typeface.NORMAL)
        candidateCommentPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.COMMENT, Typeface.NORMAL)
    }

    fun applyDensity(density: Float) {
        dividerPaint.strokeWidth = 1f * density
        toolbarTextPaint.textSize = 16f * density
        candidateTextPaint.textSize = CANDIDATE_TEXT_DP * density
        selectedCandidateTextPaint.textSize = CANDIDATE_TEXT_DP * density
        candidateIndexPaint.textSize = candidateIndexSizeDp * density
        candidateCommentPaint.textSize = candidateCommentSizeDp * density
    }
}
