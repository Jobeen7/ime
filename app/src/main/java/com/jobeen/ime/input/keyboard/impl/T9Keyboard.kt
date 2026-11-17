package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.symbol.SharedPrefsT9QuickSymbolsStore
import com.jobeen.ime.skin.symbol.SharedPrefsUserSymbolBarStore
import com.jobeen.ime.skin.symbol.T9QuickSymbolsOps
import com.jobeen.ime.skin.symbol.UserSymbolBarOps
import com.jobeen.ime.skin.symbol.UserSymbolBarStore
import com.jobeen.ime.ui.SymbolBarSettingsActivity
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.newlineKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.mixedAlphabetKey
import com.jobeen.ime.input.keyboard.key.peroidKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.segmentKey
import com.jobeen.ime.input.keyboard.key.sidePannelKey
import com.jobeen.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(
        context,
        EnterKeyVariant.resolve(context),
    ),
) : BaseKeyboard(
    context,
    colors,
    layout,
), ISidePanelKeyboard {
    /** 九宫格有专用换行键（第二行那颗）：回车键决策与显示据此去换行。 */
    override val hasDedicatedNewlineKey: Boolean = true
    private val fullWidthPunctuations = listOf("，", "。", "？", "！", "：", "~", "...")
    private val halfWidthPunctuations = listOf(",", ".", "!", "?", ":", "~", "...")
    var punctuations = fullWidthPunctuations
    private var state: PunctuationMode = PunctuationMode.FullWidth

    /**
     * 左竖栏符号源（2026-11-12 九宫格左竖栏重做）：与符号面板共用
     * 同一份用户符号偏好；设置页改动后下次无候选即跟变。
     */
    private val userSymbolStore: UserSymbolBarStore = SharedPrefsUserSymbolBarStore(context)

    init {
        this.updatePunctuationMode(state)
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    /**
     * 左竖栏“自定义”入口（2026-11-12 他定，12-12 改）：不是固定底栏，
     * 是符号列表最后一项——放在最后一个符号（＆）后面，随列表一起滑；
     * 滑到底才看见。两行字（⊕／自定义），字色取 Variant.Normal
     *（默认键字色，不加颜色），点开符号设置页。候选拼音态不显示。
     * 字号随格高缩放（I-L5，见 SidePanelRender.fitTextSizePx）：格矮
     * 时缩字号，保证两行在格内显示全；横屏单行 workaround 已删。
     */
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

    /** 左竖栏符号 KeyDef：用户 16 符号＋末尾“自定义”入口（共 17 项，可滑）。 */
    private fun symbolItemDefs(): List<KeyDef> =
        userSymbolStore.load().symbols.map { ch ->
            // WO30 §3(9)：符号按标点模式换算后显示与提交（半角模式出半角
            // 标点，全角模式出全角标点）；自定义入口与拼音候选条不受影响。
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
            // 无候选：左竖栏改用户 16 符号（替 7 标点），末尾跟“自定义”入口。
            super.updateSidePanel(symbolItemDefs())
            return
        }
        // 有候选拼音：竖栏显示拼音候选。
        // 与候选网格侧栏共用同一份构建缓存，避免同批数据每键重复全量构建
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

    companion object {
        const val NAME = "T9"

        /**
         * 生效布局（详设 §4.2 接缝）：出厂布局经当前皮肤快照的覆写
         * 装配；无快照/无命中时恒等返回出厂布局。编辑器预览传入
         * 显式 layout 时不走此路（不碰全局快照槽，详设 §10.2-7）。
         * 回车变体按 EnterKeyVariant 现算（皮肤生效跟皮肤、无皮肤
         * 跟内置主题），覆写在其结果上做。
         */
        fun effectiveLayout(context: Context, returnVariant: Variant): List<List<KeyDef>> {
            // T9 快捷符号（长按句号键弹窗）：偏好里读，改动下次打开键盘生效。
            val raw = buildLayout(
                returnVariant,
                SharedPrefsT9QuickSymbolsStore(context).load().symbols,
            )
            return raw
        }

        /**
         * T9 快捷符号弹窗（纯函数）：空列返回 null（调用方回落旧行为，
         * 句号键长按仍进符号板）；非空时每符号一项 CommitAction，
         * 滑动选择后抬手上屏（Popup.Keyboard 单行格，见 BaseKeyboard）。
         * 注意：单行每格 42dp＋4dp 间距，12 格约 560dp 超过手机屏宽，
         * PopupOverflow 只夹位置不缩宽度——9 格起边缘格需大滑动才
         * 够到（已知缺口，见 T9QuickSymbols 注释）。
         */
        fun quickSymbolsPopup(symbols: List<String>): KeyDef.Popup.Keyboard? =
            if (symbols.isEmpty()) {
                null
            } else {
                KeyDef.Popup.Keyboard(
                    label = "快捷符号",
                    keys = symbols.map { KeyboardAction.CommitAction(it) },
                )
            }

        // 布局按构造时现算（不再是类加载时单例）：回车变体由调用方
        // 经 EnterKeyVariant 传入，静态单例会在主题间串色
        // [quickSymbols]：T9 快捷符号（默认 6 个）；句号键长按弹窗。
        // 弹窗接管长按槽（BaseKeyboard：弹窗优先于长按动作），故挂
        // 弹窗时句号键长按不再直进符号板——符号板仍可经 ?123→数字键
        // 符号键到达，26 键句号键长按也不动。
        fun buildLayout(
            returnVariant: Variant,
            quickSymbols: List<String> = T9QuickSymbolsOps.DEFAULT_SYMBOLS,
        ): List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4),
                segmentKey(percentWidth = 0.22333f, id = KeyIds.T9.T9_SEGMENT),
                mixedAlphabetKey("2", "ABC", 0.22333f, id = KeyIds.T9.T9_KEY_2),
                mixedAlphabetKey("3", "DEF", 0.22333f, id = KeyIds.T9.T9_KEY_3),
                backspaceKey(0.18f, id = KeyIds.T9.T9_DELETE),
            ),
            listOf(
                mixedAlphabetKey("4", "GHI", 0.22333f, id = KeyIds.T9.T9_KEY_4),
                mixedAlphabetKey("5", "JKL", 0.22333f, id = KeyIds.T9.T9_KEY_5),
                mixedAlphabetKey("6", "MNO", 0.22333f, id = KeyIds.T9.T9_KEY_6),
                newlineKey(0.18f, id = KeyIds.T9.T9_NEWLINE),
            ),
            listOf(
                mixedAlphabetKey("7", "PQRS", 0.22333f, id = KeyIds.T9.T9_KEY_7),
                mixedAlphabetKey("8", "TUV", 0.22333f, id = KeyIds.T9.T9_KEY_8),
                mixedAlphabetKey("9", "WXYZ", 0.22333f, id = KeyIds.T9.T9_KEY_9),
                returnKey(percentWidth = 0.18f, rowSpan = 2, variant = returnVariant, id = KeyIds.T9.T9_ENTER),
            ),
            listOf(
                layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f, id = KeyIds.T9.T9_LAYOUT_NUMBER),
                peroidKey(percentWidth = 0.13f, id = KeyIds.T9.T9_PERIOD, popup = quickSymbolsPopup(quickSymbols)),
                spaceKey(percentWidth = 0.41f, id = KeyIds.T9.T9_SPACE),
                schemaSwitchKey(0.13f, id = KeyIds.T9.T9_SCHEMA),
            ),
        )
    }

    override fun name(): String {
        return NAME
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

/**
 * 左竖栏“自定义”入口文案（I-L5 纯函数）：恒两行“⊕\n自定义”；
 * 横屏单行 workaround 已删（仍截断），改由绘制期随格高缩字号
 * （SidePanelRender.fitTextSizePx），保证两行在格内显示全。
 * 字号／字色／行为一律不动，键宽与行和不动。
 */
internal fun customEntryLabel(): String = "⊕\n自定义"
