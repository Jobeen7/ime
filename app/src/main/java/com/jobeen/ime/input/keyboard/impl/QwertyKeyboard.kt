package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.R
import com.jobeen.ime.base.util.PunctuationUtil
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.input.keyboard.key.AltTextKeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.ImageKeyView
import com.jobeen.ime.input.keyboard.key.TextKeyView
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.layout.BuiltinLayoutApplier
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.input.keyboard.key.alphabetKey
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.capsLockKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.peroidKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class QwertyKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(
        context,
        EnterKeyVariant.resolve(context),
    ),
    private val previewMode: Boolean = false,
) : BaseKeyboard(
    context,
    colors,
    layout,
) {

    enum class CapsState { None, Once, Lock }

    var punctuationState: PunctuationMode = PunctuationMode.FullWidth

    companion object {
        const val NAME = "Qwerty"

        /**
         * 生效布局（详设 §4.2 接缝）：出厂布局经当前皮肤快照的覆写
         * 装配；无快照/无命中时恒等返回出厂布局。编辑器预览传入
         * 显式 layout 时不走此路（不碰全局快照槽，详设 §10.2-7）。
         * 出厂布局带 returnVariant（EnterKeyVariant 现算：皮肤生效跟
         * 皮肤、无皮肤跟内置主题），覆写在其结果上做。
         */
        fun effectiveLayout(context: Context, returnVariant: Variant): List<List<KeyDef>> {
            val raw = buildLayout(returnVariant)
            val snapshot = SkinSnapshotProvider.current(context) ?: return raw
            // 内置布局覆盖（2026-10-28）：皮肤含本键盘的内置布局文件时
            // 按文件重排（出厂键本体 + 文件排版/动作槽），再走既有覆写
            // 装配（样式/图片/字号等）；无覆盖/排空时恒用出厂布局。
            val arranged = snapshot.builtinLayouts[NAME]?.let { override ->
                BuiltinLayoutApplier.apply(
                    raw, override,
                    landscape = context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE,
                    placeholders = PlaceholderValues.current(context),
                )
            } ?: raw
            return KeyOverrideApplier.apply(
                NAME, arranged, snapshot, PlaceholderValues.current(context),
                scheme = snapshot.effectiveColorScheme(SkinColorMode.isDark(context)),
                // 接线轮 R1/R2：构建期取当前锁定通知与条件门控集。
                notifications = com.jobeen.ime.skin.runtime.SkinRuntimeState.latchedNotifications(),
                gatedStyleNames = com.jobeen.ime.skin.runtime.SkinRuntimeState.gatedStyleNames(),
                activeStyleNames = com.jobeen.ime.skin.runtime.SkinRuntimeState.activeStyleNames(),
                mnemonicEnabled = com.jobeen.ime.data.manager.KeyboardManager.Keyboard.Mnemonic.isEnabled(context),
                decorationEnabled = com.jobeen.ime.data.manager.KeyboardManager.Keyboard.Decoration.isEnabled(context),
            )
        }

        /**
         * onAction 变换的纯逻辑（自 onAction 抽取，行为逐项等价）：
         * 返回（下发动作， 目标 Caps 状态）——下发动作为 null 表示
         * 就地消费；目标 Caps 状态为 null 表示不切换。
         *
         * 裁定 B2 的字面通道在此成立：LiteralCommitAction 不命中
         * CommitAction 的标点转换、也不命中 KeySequenceAction 的
         * Caps 大写，原样下发（详设 §3.4）；出厂两类型照旧变换。
         */
        internal fun transformAction(
            action: KeyboardAction,
            capsState: CapsState,
            fullWidthPunctuation: Boolean,
        ): Pair<KeyboardAction?, CapsState?> = when (action) {
            is KeyboardAction.CapsAction -> null to when (capsState) {
                CapsState.None -> CapsState.Once
                CapsState.Once -> CapsState.Lock
                CapsState.Lock -> CapsState.None
            }

            is KeyboardAction.CommitAction -> KeyboardAction.CommitAction(
                PunctuationUtil.convert(action.text, fullWidthPunctuation),
            ) to null

            is KeyboardAction.KeySequenceAction -> {
                if (capsState != CapsState.None &&
                    KeyEvent.SequenceEvent.isLowerAlphabet(action.sequence)
                ) {
                    KeyboardAction.KeySequenceAction(action.sequence.uppercase()) to
                        if (capsState == CapsState.Once) CapsState.None else null
                } else {
                    action to null
                }
            }

            else -> action to null
        }

        fun buildLayout(returnVariant: Variant): List<List<KeyDef>> {
            return listOf(
                listOf(
                    alphabetKey("q", "1", id = KeyIds.Qwerty.KEY_Q),
                    alphabetKey("w", "2", id = KeyIds.Qwerty.KEY_W),
                    alphabetKey("e", "3", id = KeyIds.Qwerty.KEY_E),
                    alphabetKey("r", "4", id = KeyIds.Qwerty.KEY_R),
                    alphabetKey("t", "5", id = KeyIds.Qwerty.KEY_T),
                    alphabetKey("y", "6", id = KeyIds.Qwerty.KEY_Y),
                    alphabetKey("u", "7", id = KeyIds.Qwerty.KEY_U),
                    alphabetKey("i", "8", id = KeyIds.Qwerty.KEY_I),
                    alphabetKey("o", "9", id = KeyIds.Qwerty.KEY_O),
                    alphabetKey("p", "0", id = KeyIds.Qwerty.KEY_P),
                ),
                listOf(
                    alphabetKey("a", "~", id = KeyIds.Qwerty.KEY_A),
                    alphabetKey("s", "!", id = KeyIds.Qwerty.KEY_S),
                    alphabetKey("d", "@", id = KeyIds.Qwerty.KEY_D),
                    alphabetKey("f", "#", id = KeyIds.Qwerty.KEY_F),
                    alphabetKey("g", "$", id = KeyIds.Qwerty.KEY_G, mainTextTranslationY = -2),
                    alphabetKey("h", "%", id = KeyIds.Qwerty.KEY_H),
                    alphabetKey("j", "^", id = KeyIds.Qwerty.KEY_J, altTextTranslationY = 4),
                    alphabetKey("k", "&", id = KeyIds.Qwerty.KEY_K),
                    alphabetKey("l", "*", id = KeyIds.Qwerty.KEY_L, altTextTranslationY = 4),
                ),
                listOf(
                    capsLockKey(id = KeyIds.Qwerty.KEY_CAPS),
                    alphabetKey("z", "(", id = KeyIds.Qwerty.KEY_Z),
                    alphabetKey("x", ")", id = KeyIds.Qwerty.KEY_X),
                    alphabetKey("c", ":", id = KeyIds.Qwerty.KEY_C),
                    alphabetKey("v", ";", id = KeyIds.Qwerty.KEY_V),
                    alphabetKey("b", ",", id = KeyIds.Qwerty.KEY_B),
                    alphabetKey("n", "?", id = KeyIds.Qwerty.KEY_N),
                    alphabetKey("m", "/", id = KeyIds.Qwerty.KEY_M),
                    backspaceKey(id = KeyIds.Qwerty.KEY_DELETE),
                ),
                listOf(
                    layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f, id = KeyIds.Qwerty.KEY_LAYOUT_NUMBER),
                    peroidKey(percentWidth = 0.13f, id = KeyIds.Qwerty.KEY_PERIOD),
                    spaceKey(percentWidth = 0.44f, id = KeyIds.Qwerty.KEY_SPACE),
                    schemaSwitchKey(0.13f, id = KeyIds.Qwerty.KEY_SCHEMA),
                    returnKey(percentWidth = 0.15f, variant = returnVariant, id = KeyIds.Qwerty.KEY_ENTER),
                ),
            )
        }
    }

    private var capsState = CapsState.None
    private val letterKeyViews = mutableListOf<AltTextKeyView>()
    private var capsKeyView: ImageKeyView? = null

    init {
        this.updatePunctuationMode(punctuationState)
        for (row in keyRows) {
            for (i in 0 until row.childCount) {
                when (val child = row.getChildAt(i)) {
                    is AltTextKeyView -> {
                        val text = child.mainText.text.toString()
                        if (text.length == 1 && text[0].isLetter()) {
                            letterKeyViews.add(child)
                        }
                    }

                    is ImageKeyView -> {
                        if ((child.def as KeyDef.Appearance.Image).src == R.drawable.ic_keyboard_capslock_none) {
                            capsKeyView = child
                        }
                    }
                }
            }
        }
    }

    override fun onAction(action: KeyboardAction) {
        if (previewMode && action is KeyboardAction.CapsAction) {
            // 编辑器预览（详设 §6.3）：Caps 是本类唯一就地消费（切换
            // 状态）的动作，预览中吞掉防状态污染；其余变换都是纯
            // 文本变换且监听为空，本就无副作用
            DiagLog.d(
                "editor", "preview_action_swallowed", mapOf("action" to "CapsAction"),
            )
            return
        }
        val (transformed, capsTarget) = transformAction(
            action, capsState, punctuationState == PunctuationMode.FullWidth,
        )
        if (capsTarget != null) switchCapsState(capsTarget)
        if (transformed == null) return
        super.onAction(transformed)
    }

    override fun onAttach() {
        super.onAttach()
        capsState = CapsState.None
        updateKeyTextForState(capsState)
    }

    override fun name(): String {
        return NAME
    }

    private fun switchCapsState(target: CapsState? = null) {
        capsState = target ?: when (capsState) {
            CapsState.None -> CapsState.Once
            CapsState.Once -> CapsState.Lock
            CapsState.Lock -> CapsState.None
        }
        updateKeyTextForState(capsState)
    }


    private fun updateKeyTextForState(state: CapsState) {
        val uppercase = state != CapsState.None
        for (kv in letterKeyViews) {
            val text = kv.mainText.text.toString()
            kv.updateText(if (uppercase) text.uppercase() else text.lowercase())
        }
        capsKeyView?.img?.setImageResource(
            when (state) {
                CapsState.None -> R.drawable.ic_keyboard_capslock_none
                CapsState.Once -> R.drawable.ic_keyboard_capslock_once
                CapsState.Lock -> R.drawable.ic_keyboard_capslock_lock
            }
        )
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        punctuationState = mode
        super.updatePunctuationMode(mode)
    }
}
