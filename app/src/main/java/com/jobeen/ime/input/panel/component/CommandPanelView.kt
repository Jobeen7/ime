// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jobeen.ime.R
import com.jobeen.ime.data.ai.AiStreamEvent
import com.jobeen.ime.data.database.AIInstruction
import com.jobeen.ime.data.keyboard.theme.KeyboardColors

/**
 * 键盘命令面板（工具栏“命令面板”入口，View 体系；与性能面板同构）：
 * 顶部标题「命令面板」＋关闭（红色 ×）＋运行中状态行；下方滚动列
 * 出 AiRepository 里的全部指令（名＋说明），点一条即走确认再跑。
 *
 * - 指令列表不在此取数：KawaiiPanel 进态时加载并调 [setInstructions]；
 *   列表随命令面板设置页走——新加的自定义指令下次打开自动出现，
 *   不写死四条。
 * - 本面板不直接碰服务层／网络：点行只调 [onInstructionClick]，
 *   运行、确认、回填都在 KawaiiPanel；关闭只调 [onClose]。
 * - 运行中行不可点（[setRunning]），防重复提交同一指令。
 */
@SuppressLint("ViewConstructor")
class CommandPanelView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : ComponentView(context, colors) {

    /** 点某条指令：KawaiiPanel 接线——快照输入、弹确认、跑指令。 */
    var onInstructionClick: ((AIInstruction) -> Unit)? = null

    /** 关闭回调：KawaiiPanel 接线为 returnToKeyboard()。 */
    var onClose: (() -> Unit)? = null

    private var running: Boolean = false

    private val titleView = TextView(context).apply {
        text = context.getString(R.string.ai_title)
        setTextSize(17f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.panel.candidateText)
    }

    private val closeView = TextView(context).apply {
        text = "✕"
        setTextSize(20f)
        setTextColor(CLOSE_RED)
        contentDescription = context.getString(R.string.close)
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        setOnClickListener { onClose?.invoke() }
    }

    /** 运行中状态行：平时隐藏，跑指令时显示「正在生成…」并禁点各行。 */
    private val statusView = TextView(context).apply {
        text = context.getString(R.string.ai_running)
        setTextSize(14f)
        setTextColor(colors.panel.candidateComment ?: colors.panel.candidateIndex)
        visibility = View.GONE
        val hPad = (16 * context.resources.displayMetrics.density).toInt()
        val vPad = (4 * context.resources.displayMetrics.density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
    }

    private val rowsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (16 * context.resources.displayMetrics.density).toInt()
            val vPad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(closeView)
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            ScrollView(context).apply {
                addView(
                    rowsContainer,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            root,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        titleView.setTextColor(newColors.panel.candidateText)
        statusView.setTextColor(
            newColors.panel.candidateComment ?: newColors.panel.candidateIndex,
        )
    }

    /** 重建指令行：空表显示一行「暂无指令」（不可点）；行点按走回调。 */
    fun setInstructions(instructions: List<AIInstruction>) {
        rowsContainer.removeAllViews()
        if (instructions.isEmpty()) {
            rowsContainer.addView(emptyRowView())
            return
        }
        for (instruction in instructions) {
            rowsContainer.addView(instructionRowView(instruction))
        }
    }

    /** 运行中：状态行显示、各行禁点；跑完恢复。 */
    fun setRunning(isRunning: Boolean) {
        running = isRunning
        statusView.visibility = if (isRunning) View.VISIBLE else View.GONE
        for (i in 0 until rowsContainer.childCount) {
            rowsContainer.getChildAt(i).isClickable = !isRunning
        }
    }

    private fun emptyRowView(): TextView =
        TextView(context).apply {
            text = context.getString(R.string.ai_no_instructions)
            setTextSize(15f)
            setTextColor(colors.panel.candidateComment ?: colors.panel.candidateIndex)
            val vPad = (10 * context.resources.displayMetrics.density).toInt()
            setPadding(0, vPad, 0, vPad)
        }

    private fun instructionRowView(instruction: AIInstruction): ViewGroup {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = !running
            val vPad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(0, vPad, 0, vPad)
            setOnClickListener {
                if (!running) onInstructionClick?.invoke(instruction)
            }
        }
        row.addView(
            TextView(context).apply {
                text = instruction.name
                setTextSize(16f)
                setTextColor(colors.panel.candidateText)
            },
        )
        if (instruction.detail.isNotBlank()) {
            row.addView(
                TextView(context).apply {
                    text = instruction.detail
                    setTextSize(14f)
                    setTextColor(colors.panel.candidateComment ?: colors.panel.candidateIndex)
                },
            )
        }
        return row
    }

    companion object {
        /** 关闭按钮红（×），与性能面板同值固定，不跟皮肤走。 */
        private val CLOSE_RED = 0xFFF44336.toInt()

        /**
         * 输入取数口径（纯函数，可单测）：优先输入框选中文字，
         * 没选中才取剪贴板；两边都空返回空串（调用方弹“没有可发送的文字”，不跑）。
         */
        fun resolveInput(selectedText: String, clipboardText: String): String =
            if (selectedText.isNotBlank()) selectedText else clipboardText

        /**
         * 流错误文案口径（纯函数，可单测；与设置页 AiSettingsScreen
         * 同款）：messageRes 优先，其次 missing_token 用缺 token
         * 文案，其余用事件自带 message。
         */
        fun resolveErrorText(
            error: AiStreamEvent.Error,
            missingTokenText: String,
            resolveRes: (Int) -> String,
        ): String = error.messageRes?.let { resolveRes(it) }
            ?: if (error.code == "missing_token") missingTokenText else error.message

        /** 跑完是否值得回填（纯函数，可单测）：空白结果不回填。 */
        fun shouldCommit(result: String): Boolean = result.isNotBlank()
    }
}
