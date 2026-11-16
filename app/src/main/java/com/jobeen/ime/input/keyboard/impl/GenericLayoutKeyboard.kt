package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.component.PinYinKeyDefCache
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.symbol.SharedPrefsUserSymbolBarStore
import com.jobeen.ime.skin.symbol.UserSymbolBarOps
import com.jobeen.ime.skin.symbol.UserSymbolBarStore
import com.jobeen.ime.ui.SymbolBarSettingsActivity

/**
 * 通用布局键盘（B3 详设 §3.1/§3.3）：自创布局的运行时载体，
 * 只喂数据——构建、渲染、跨行 span、手势装配全部走 [BaseKeyboard]
 * 与出厂布局同一条路径，手势引擎零改动（铁律 5）。
 *
 * name 契约：返回其运行时名 `custom:<layoutId>`，与状态管理器的
 * 按名缓存一致；同一布局一个缓存实例，布局新代次生效走既有
 * rebuild() 清缓存重建。
 *
 * 左竖栏（2026-11-16 debug 问题 1/2）：自创布局的集合键
 * （t9Symbols／numericSymbols）在 LayoutKeyDefBuilder 已转成
 * SidePannel 外观，本类按出厂口径实例化真正的 SidePanelKeyView
 * 并喂面板项，不新写滚动：
 * - T9 布局：无候选时喂用户 16 符号＋末尾“自定义”入口（共 17 项，
 *   与出厂 T9Keyboard 同款，经 SymbolKeyboard.buildSidePanelItemDefs）；
 *   有候选拼音时喂拼音候选（PinYinKeyDefCache，与出厂同款）；
 * - 数字布局：恒喂数字符号集（皮肤 numericSymbols 为准，未声明时
 *   按源包 13 项），＋末尾“自定义”入口，与 T9 符号栏同交互；
 * - 无集合键的布局不喂面板。
 * 初始喂数在 init＋onAttach（与出厂 NumberKeyboard／T9Keyboard
 * 同口径），标点模式变化时重喂。
 *
 * updatePunctuationMode / updateEditorInfo / updateSpaceKeyText
 * 均由 BaseKeyboard 实现，本类只重写标点模式（为左栏换算记态，
 * 与 T9Keyboard 同口径）。
 */
@SuppressLint("ViewConstructor")
class GenericLayoutKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    private val layoutName: String,
    keyLayout: List<List<KeyDef>>,
) : BaseKeyboard(context, colors, keyLayout), ISidePanelKeyboard {

    override fun name(): String = layoutName

    /** 自创布局的集合键模式：T9 左栏／数字左栏／无。 */
    private enum class PanelMode { NONE, T9, NUMERIC }

    private val panelMode: PanelMode = detectPanelMode(keyLayout)

    private val userSymbolStore: UserSymbolBarStore = SharedPrefsUserSymbolBarStore(context)

    private var punctuationState: PunctuationMode = PunctuationMode.FullWidth

    init {
        setSidePanelItemListener { action -> onAction(action) }
        onPossibleCandidatePinYin(emptyList())
    }

    override fun onAttach() {
        onPossibleCandidatePinYin(emptyList())
        super.onAttach()
    }

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        when (panelMode) {
            PanelMode.NONE -> return
            PanelMode.T9 -> if (data.isEmpty()) {
                super.updateSidePanel(t9PanelItems())
            } else {
                super.updateSidePanel(PinYinKeyDefCache.get(data))
            }
            // 数字左栏不吃拼音候选：恒喂数字符号集（与出厂 NumberKeyboard
            // 忽略 data 同口径）。
            PanelMode.NUMERIC -> super.updateSidePanel(numericPanelItems())
        }
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        val changed = mode != punctuationState
        punctuationState = mode
        if (changed) {
            onPossibleCandidatePinYin(emptyList())
        }
        super.updatePunctuationMode(mode)
    }

    /** “自定义”入口就地处理：直接起符号设置页，不下发引擎、不进服务分发（与 T9Keyboard 同款）。 */
    override fun onAction(action: KeyboardAction) {
        if (action is KeyboardAction.OpenSymbolBarSettingsAction) {
            val intent = Intent(context, SymbolBarSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        super.onAction(action)
    }

    /** T9 左竖栏项：用户 16 符号（按标点模式换算）＋末尾“自定义”入口（共 17 项）。 */
    private fun t9PanelItems(): List<KeyDef> =
        SymbolKeyboard.buildSidePanelItemDefs(
            userSymbolStore.load().symbols.map { ch ->
                UserSymbolBarOps.displaySymbol(ch, punctuationState)
            },
        )

    /** 数字左竖栏项：皮肤 numericSymbols 为准，未声明时按源包 13 项，＋末尾“自定义”入口。 */
    private fun numericPanelItems(): List<KeyDef> =
        SymbolKeyboard.buildSidePanelItemDefs(
            SkinSnapshotProvider.peek()?.numericSymbols?.items?.map { it.commitText }
                ?.takeIf { it.isNotEmpty() }
                ?: KeyOverrideApplier.NUMERIC_SYMBOLS_COLLECTION_DEFAULT,
        )

    companion object {
        /** 布局内第一个集合键定模式；两个都有时取先出现的（单布局只会有一个）。 */
        private fun detectPanelMode(keyLayout: List<List<KeyDef>>): PanelMode {
            for (row in keyLayout) {
                for (def in row) {
                    if (def.appearance !is KeyDef.Appearance.SidePannel) continue
                    when (LayoutModel.parseLogicalId(def.id ?: "")?.second) {
                        KeyOverrideApplier.T9_SYMBOLS_COLLECTION_STABLE_ID -> return PanelMode.T9
                        KeyOverrideApplier.NUMERIC_SYMBOLS_COLLECTION_STABLE_ID ->
                            return PanelMode.NUMERIC
                    }
                }
            }
            return PanelMode.NONE
        }
    }
}
