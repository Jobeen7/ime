package com.ninthsoft.ime.input.panel

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors

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
    var toolbarPressedColor: Int = 0
    var toolbarIconColor: Int = 0
    var keyboardBackground: Int = 0

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
    }

    fun applyDensity(density: Float) {
        dividerPaint.strokeWidth = 1f * density
        toolbarTextPaint.textSize = 16f * density
        candidateTextPaint.textSize = 17f * density
        candidateIndexPaint.textSize = 14f * density
    }
}
