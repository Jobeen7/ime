// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.symbol.SharedPrefsUserSymbolBarStore
import com.jobeen.ime.skin.symbol.UserSymbolBarOps
import com.jobeen.ime.skin.symbol.UserSymbolBarStore
import com.jobeen.ime.ui.SymbolBarSettingsActivity

/**
 * 紧凑拼音键盘基类（14/18 键共用，T9Keyboard 的侧栏逻辑同款，
 * 只写一次；T9 本体不动，不复用它的代码——避免改动已验证的 T9）：
 *
 * - 左竖栏：无候选时显示用户符号栏（16 符号＋末尾“自定义”入口，
 *   与 T9 同源同序）；有候选拼音时显示拼音候选。
 * - 标点模式：全角／半角两套标点表，切换时刷新竖栏。
 * - “自定义”入口就地处理：直接起符号设置页。
 */
@SuppressLint("ViewConstructor")
abstract class CompactKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>>,
) : BaseKeyboard(
    context,
    colors,
    layout,
), ISidePanelKeyboard {
    /** 14/18 键与九宫格一样有专用换行键（第二行那颗）。 */
    override val hasDedicatedNewlineKey: Boolean = true
    private val fullWidthPunctuations = listOf("，", "。", "？", "！", "：", "~", "...")
    private val halfWidthPunctuations = listOf(",", ".", "!", "?", ":", "~", "...")
    var punctuations = fullWidthPunctuations
    private var state: PunctuationMode = PunctuationMode.FullWidth

    private val userSymbolStore: UserSymbolBarStore = SharedPrefsUserSymbolBarStore(context)

    init {
        this.updatePunctuationMode(state)
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    private fun customEntryDef(): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = customEntryLabel(),
            textSize = 12f,
            percentWidth = 0.5f,
            margin = false,
            variant = Variant.Normal,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.OpenSymbolBarSettingsAction)),
    )

    /** 左竖栏符号 KeyDef：用户 16 符号＋末尾“自定义”入口（与 T9 同款）。 */
    private fun symbolItemDefs(): List<KeyDef> =
        userSymbolStore.load().symbols.map { ch ->
            val display = UserSymbolBarOps.displaySymbol(ch, state)
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = display,
                    textSize = 15f,
                    percentWidth = 0.5f,
                    margin = false,
                    variant = Variant.Alternative
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(display))),
            )
        } + customEntryDef()

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (data.isEmpty()) {
            super.updateSidePanel(symbolItemDefs())
            return
        }
        super.updateSidePanel(com.jobeen.ime.input.panel.component.PinYinKeyDefCache.get(data))
    }

    /** “自定义”入口就地处理：直接起符号设置页，不下发引擎、不进服务分发。 */
    override fun onAction(action: KeyboardAction) {
        if (action is KeyboardAction.OpenSymbolBarSettingsAction) {
            val intent = Intent(context, SymbolBarSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        super.onAction(action)
    }

    override fun onAttach() {
        this.onPossibleCandidatePinYin(emptyList())
        super.onAttach()
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        val changed = mode != state
        state = mode
        punctuations = when (mode) {
            PunctuationMode.FullWidth -> fullWidthPunctuations
            PunctuationMode.HalfWidth -> halfWidthPunctuations
        }
        if (changed) {
            this.onPossibleCandidatePinYin(emptyList())
        }
        super.updatePunctuationMode(mode)
    }
}
