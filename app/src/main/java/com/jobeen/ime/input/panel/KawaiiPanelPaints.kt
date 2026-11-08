package com.jobeen.ime.input.panel

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.CandidateSpacingPolicy
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

/**
 * 面板配色按类型取（G1）：快照存在时按当前键盘类型取样式集色板，
 * 无快照时走全局 resolve。type = null 时取包级（旧行为）。
 * 调用方（面板刷新链）把当前键盘类型传进来；面板不直接依赖
 * KeyboardStateManager（窗口层持有类型，面板只收参数）。
 */
internal fun panelColorScheme(context: Context, type: String?): KeyboardColors.ColorScheme {
    val snapshot = SkinSnapshotProvider.peek()
    return snapshot?.effectiveColorSchemeForType(type, SkinColorMode.isDark(context))
        ?: KeyboardColors.resolve(context)
}

class Paints(context: Context) {
    companion object {
        /** 候选主文字字号（dp）：三参考皮肤横条 candidateStyle.textFontSize 皆 18。 */
        const val CANDIDATE_TEXT_DP = 18f
        /**
         * 候选序号字号（dp，Jime 自有默认）：皮肤缺失时回落此值。
         * 2026-11-01 纠正：恢复纠正前既有值 16——此前 18 是单套
         * 参考皮肤的值，不作通用回落（字号是按皮肤取值）。
         */
        const val CANDIDATE_INDEX_DP = 16f
        /**
         * 候选注释字号（dp，Jime 自有默认）：皮肤缺失时回落此值。
         * 2026-11-01 纠正：恢复纠正前既有值 16（序号与注释同取
         * 副字号）——此前 11 是单套参考皮肤的值，不作通用回落。
         * 竖格 commentFontSize 不走此画笔：网格只画主文字。
         */
        const val CANDIDATE_COMMENT_DP = 16f
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
     * 同步（缺失 = Jime 默认 16 / 16），applyDensity 消费；主题刷新
     * 时 KawaiiPanelView.refreshTheme 追加 applyDensity，使字号
     * 随皮肤切换即时生效（此前 refreshTheme 只刷颜色）。
     */
    var candidateIndexSizeDp: Float = CANDIDATE_INDEX_DP
    var candidateCommentSizeDp: Float = CANDIDATE_COMMENT_DP
    /**
     * 候选主文字生效字号（候选字号参数；缺省 18）：
     * updateColors 从横条单元格样式同步（无横条样式时回落 18，
     * 与 CandidateSizeResolution.JIME_DEFAULT_TEXT_DP 同值）。
     */
    var candidateTextSizeDp: Float = CANDIDATE_TEXT_DP
    /**
     * 候选栏缩放（H2-3，倍率 0.8~1.5，默认 1.0）：updateColors
     * 从 CandidateManager 同步；applyDensity 写画笔字号时乘
     * 倍率（主文字／序号／注释同乘，保相对比例）。1.0 时与旧行为一致。
     */
    var candidateScale: Float = 1f
    // （候选字重不设变量：updateFonts 直接从快照现取，避免
    // updateColors／updateFonts 调用顺序依赖，见下。）
    /** 横条分隔线可见（S10）：默认可见，皮肤写 false 时隐藏。 */
    var candidateDividerVisible: Boolean = true
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

    fun updateColors(context: Context, type: String? = null) {
        val scheme = panelColorScheme(context, type)
        val snapshot = SkinSnapshotProvider.peek()
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
        candidateStripBg = snapshot?.horizontalCandidatesStyleFor(type)?.background
        // S10：横条分隔线可见性（未写 = 可见）。
        candidateDividerVisible =
            snapshot?.horizontalCandidatesStyleFor(type)?.dividerVisible ?: true
        // 单元格全字段优先于旧顶层字号字段，缺失回落 Jime 默认。
        candidateIndexSizeDp =
            snapshot?.horizontalCandidateStyleFor(type)?.indexSizeDp
                ?: snapshot?.candidateIndexSizeDp ?: CANDIDATE_INDEX_DP
        candidateCommentSizeDp =
            snapshot?.horizontalCandidateStyleFor(type)?.commentSizeDp
                ?: snapshot?.candidateCommentSizeDp ?: CANDIDATE_COMMENT_DP
        // 候选主文字字号（S9；主文字无旧顶层字段，只读横条样式）。
        candidateTextSizeDp =
            snapshot?.horizontalCandidateStyleFor(type)?.textSizeDp ?: CANDIDATE_TEXT_DP
        // 候选栏缩放（H2-3）：用户倍率，读 prefs（updateColors
        // 只同步变量，实际画笔字号由 applyDensity 写）。
        candidateScale = com.jobeen.ime.data.manager.CandidateManager.getCandidateScale(context)
        // 字重在 updateFonts 现取（避免与 updateColors 的调用顺序依赖）。
        candidatePressedColor = panel.candidatePressed
        preferredBackground = panel.preferredBackground
        preferredText = panel.preferredText
        preferredComment = panel.preferredComment
        preferredIndex = panel.preferredIndex
        // 工具栏按钮表面（全局样式层）：皮肤样式 presence 决定
        // 全局按键值是否生效；跟随按键时半径取生效键圆角。
        val global = GlobalStylePrefs.get(context)
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
        // S9：候选字重（≥600 粗体，其余常规；无字重时常规）。
        // 现取快照，避免依赖 updateColors 的调用顺序。
        val candidateWeight =
            SkinSnapshotProvider.peek()?.horizontalCandidateStyle?.textWeight?.weight
        val candidateStyle =
            if ((candidateWeight ?: 400) >= 600) Typeface.BOLD else Typeface.NORMAL
        candidateTextPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.CANDIDATE, candidateStyle)
        selectedCandidateTextPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.CANDIDATE, candidateStyle)
        candidateIndexPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.INDEX, Typeface.NORMAL)
        candidateCommentPaint.typeface =
            GlobalFonts.typefaceFor(context, FontRole.COMMENT, Typeface.NORMAL)
    }

    fun applyDensity(density: Float) {
        dividerPaint.strokeWidth = 1f * density
        toolbarTextPaint.textSize = 16f * density
        // 候选栏缩放（H2-3）：三角色字号同乘倍率；纯函数钳范围，
        // 1.0 时与旧行为逐值一致。
        candidateTextPaint.textSize = CandidateSpacingPolicy.scaledTextSizeDp(
            candidateTextSizeDp, candidateScale,
        ) * density
        selectedCandidateTextPaint.textSize = CandidateSpacingPolicy.scaledTextSizeDp(
            candidateTextSizeDp, candidateScale,
        ) * density
        candidateIndexPaint.textSize = CandidateSpacingPolicy.scaledTextSizeDp(
            candidateIndexSizeDp, candidateScale,
        ) * density
        candidateCommentPaint.textSize = CandidateSpacingPolicy.scaledTextSizeDp(
            candidateCommentSizeDp, candidateScale,
        ) * density
    }
}
