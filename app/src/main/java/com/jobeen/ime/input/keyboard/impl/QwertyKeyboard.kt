package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.R
import com.jobeen.ime.base.util.PunctuationUtil
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.input.EnterDecision
import com.jobeen.ime.input.EnterFieldAction
import com.jobeen.ime.input.EnterFieldBoundary
import com.jobeen.ime.input.EnterOutcome
import com.jobeen.ime.input.keyboard.key.AltTextKeyView
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.ImageKeyView
import com.jobeen.ime.input.keyboard.key.TextKeyView
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.StyleStatePriority
import com.jobeen.ime.skin.layout.BuiltinLayoutApplier
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.input.keyboard.key.alphabetKey
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.capsLockKey
import com.jobeen.ime.input.keyboard.key.commaKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.spaceIconKey

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

    enum class CapsState {
        None, Once, Lock,

        /** 按住上档（审计 E：momentary shift）：按住期间大写，抬手即回。 */
        Momentary,
    }

    var punctuationState: PunctuationMode = PunctuationMode.FullWidth

    companion object {
        const val NAME = "Qwerty"

        /**
         * 26 键竖屏键盘高（源包 pinyin_26 keyboardHeight 25vh；见
         * KeyboardWindowView.contentHeight：无皮肤＋竖屏＋26 键时取此值，
         * 用户按类型显式设的高度优先）。
         */
        const val HEIGHT_PERCENT_PORTRAIT = 25

        /**
         * 绿回车的作用范围（源包 $returnKeyType）：绿 = 1 go／4 next／
         * 6 search／7 send／9 done；其余走灰。纯逻辑，可直接单测。
         */
        internal fun isGreenReturnAction(action: EnterFieldAction?): Boolean =
            action == EnterFieldAction.GO ||
                action == EnterFieldAction.NEXT ||
                action == EnterFieldAction.SEARCH ||
                action == EnterFieldAction.SEND ||
                action == EnterFieldAction.DONE

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
                // 按住期间 caps 键不改状态（Momentary 只由抬手结束）。
                CapsState.Momentary -> CapsState.Momentary
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

        /**
         * 按住上档按下目标（纯决策）：仅 None 时进 Momentary；
         * 已是 Once/Lock/Momentary 时按下不改状态（抬手亦不改，
         * 由 active 标志位保证成对无操作）。
         */
        internal fun momentaryDownTarget(state: CapsState): CapsState? =
            if (state == CapsState.None) CapsState.Momentary else null

        /**
         * 按住上档抬手目标（纯决策）：仅当本次按下激活且仍为
         * Momentary 时回 None；其余（未激活、期间被 caps 键改
         * 态）均不改状态。
         */
        internal fun momentaryUpTarget(state: CapsState, active: Boolean): CapsState? =
            if (active && state == CapsState.Momentary) CapsState.None else null

        fun buildLayout(returnVariant: Variant): List<List<KeyDef>> {
            return listOf(
                listOf(
                    alphabetKey("q", popup = letterPopup("q", "1", 0), id = KeyIds.Qwerty.KEY_Q),
                    alphabetKey("w", popup = letterPopup("w", "2", 1), id = KeyIds.Qwerty.KEY_W),
                    alphabetKey("e", popup = letterPopup("e", "3", 1), id = KeyIds.Qwerty.KEY_E),
                    alphabetKey("r", popup = letterPopup("r", "4", 1), id = KeyIds.Qwerty.KEY_R),
                    alphabetKey("t", popup = letterPopup("t", "5", 1), id = KeyIds.Qwerty.KEY_T),
                    alphabetKey("y", popup = letterPopup("y", "6", 1), id = KeyIds.Qwerty.KEY_Y),
                    alphabetKey("u", popup = letterPopup("u", "7", 1), id = KeyIds.Qwerty.KEY_U),
                    alphabetKey("i", popup = letterPopup("i", "8", 1), id = KeyIds.Qwerty.KEY_I),
                    alphabetKey("o", popup = letterPopup("o", "9", 1), id = KeyIds.Qwerty.KEY_O),
                    alphabetKey("p", popup = letterPopup("p", "0", 2), id = KeyIds.Qwerty.KEY_P),
                ),
                listOf(
                    // a／l 取 0.15 宽格（源包 pinyin_26 行 2 两端键宽；
                    // 键面 2/3 贴内侧的缩进在 init 里按屏宽现算，见下）。
                    alphabetKey("a", "~", percentWidth = 0.15f, id = KeyIds.Qwerty.KEY_A),
                    alphabetKey("s", "!", id = KeyIds.Qwerty.KEY_S),
                    alphabetKey("d", "@", id = KeyIds.Qwerty.KEY_D),
                    alphabetKey("f", "#", id = KeyIds.Qwerty.KEY_F),
                    alphabetKey("g", "$", id = KeyIds.Qwerty.KEY_G),
                    alphabetKey(
                        "h",
                        popup = KeyDef.Popup.Keyboard(
                            label = "H",
                            keys = listOf(
                                KeyboardAction.KeySequenceAction("https://"),
                                KeyboardAction.KeySequenceAction("http://"),
                            ),
                            initialSelectedIndex = 1,
                        ),
                        id = KeyIds.Qwerty.KEY_H,
                    ),
                    alphabetKey("j", "^", id = KeyIds.Qwerty.KEY_J),
                    alphabetKey("k", "&", id = KeyIds.Qwerty.KEY_K),
                    alphabetKey("l", "*", percentWidth = 0.15f, id = KeyIds.Qwerty.KEY_L),
                ),
                listOf(
                    capsLockKey(id = KeyIds.Qwerty.KEY_CAPS, iconSizeDp = 19f),
                    alphabetKey("z", "(", id = KeyIds.Qwerty.KEY_Z),
                    alphabetKey("x", ")", id = KeyIds.Qwerty.KEY_X),
                    alphabetKey("c", ":", id = KeyIds.Qwerty.KEY_C),
                    alphabetKey("v", ";", id = KeyIds.Qwerty.KEY_V),
                    alphabetKey("b", ",", id = KeyIds.Qwerty.KEY_B),
                    alphabetKey("n", "?", id = KeyIds.Qwerty.KEY_N),
                    alphabetKey("m", "/", id = KeyIds.Qwerty.KEY_M),
                    backspaceKey(
                        id = KeyIds.Qwerty.KEY_DELETE,
                        src = R.drawable.ic_keyboard_backspace_outline,
                        iconSizeDp = 21f,
                    ),
                ),
                listOf(
                    // 第四行（源包 pinyin_26 行 4 宽度）：123 0.21／中英
                    // 0.10／空格 0.38／，。0.10／发送 0.21，和为 1.00。
                    layoutSwitchKey(
                        "123", NumberKeyboard.NAME,
                        percentWidth = 0.21f, id = KeyIds.Qwerty.KEY_LAYOUT_NUMBER,
                        popup = KeyDef.Popup.Keyboard(
                            label = "123",
                            keys = listOf("+", "-", "=", "×", "÷", "√")
                                .map { KeyboardAction.KeySequenceAction(it) },
                            initialSelectedIndex = 1,
                        ),
                    ),
                    schemaSwitchKey(
                        0.10f, id = KeyIds.Qwerty.KEY_SCHEMA,
                        src = R.drawable.cnen, variant = Variant.Normal, iconSizeDp = 24f,
                    ),
                    spaceIconKey(percentWidth = 0.38f, id = KeyIds.Qwerty.KEY_SPACE),
                    commaKey(percentWidth = 0.10f, id = KeyIds.Qwerty.KEY_PERIOD),
                    returnKey(
                        percentWidth = 0.21f, variant = returnVariant,
                        id = KeyIds.Qwerty.KEY_ENTER, useTextLabel = true,
                    ),
                ),
            )
        }

        /**
         * 字母长按弹窗（源包 hintSymbols：[小写， 数字， 大写]，
         * 选中下标 q=0／w–o=1／p=2；动作一律 KeySequenceAction，
         * 与皮肤路径 toRuntime(Text) 同口径）。
         */
        private fun letterPopup(letter: String, digit: String, selectedIndex: Int): KeyDef.Popup.Keyboard =
            KeyDef.Popup.Keyboard(
                label = letter.uppercase(),
                keys = listOf(
                    KeyboardAction.KeySequenceAction(letter),
                    KeyboardAction.KeySequenceAction(digit),
                    KeyboardAction.KeySequenceAction(letter.uppercase()),
                ),
                initialSelectedIndex = selectedIndex,
            )
    }

    private var capsState = CapsState.None

    /** 按住上档是否由本键按下（按下时已是 Once/Lock 则按下/抬手均无操作）。 */
    private var momentaryShiftActive = false
    private val letterKeyViews = mutableListOf<AltTextKeyView>()
    private var capsKeyView: ImageKeyView? = null
    private var returnKeyView: TextKeyView? = null
    private var lastReturnOutcome: EnterOutcome? = null

    init {
        this.updatePunctuationMode(punctuationState)
        // 26 键几何（2026-11-06 施工单 3：源包键面内缩）：无皮肤时
        // 按源包键距覆写视图边距（字母横 2.5／功能横 3、纵 3.5dp，
        // 全局 Gap 用户设置只对其他键盘生效）；皮肤生效时皮肤几何
        // 优先，不覆写。行高由窗口高度（25vh）定，圆角沿用 9dp 缺省。
        val noSnapshot = SkinSnapshotProvider.peek() == null
        val density = context.resources.displayMetrics.density
        val indentDp = if (noSnapshot) {
            0.05f * context.resources.displayMetrics.widthPixels / density
        } else {
            0f
        }
        for (row in keyRows) {
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i) as? KeyView ?: continue
                when (child) {
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

                    is TextKeyView -> {
                        if (child.def.viewId == KeyView.button_return) {
                            returnKeyView = child
                        }
                    }
                }
                if (noSnapshot) {
                    val isLetterFace = child.def.variant == Variant.Normal
                    child.hMargin = ((if (isLetterFace) 2.5f else 3f) * density).toInt()
                    child.vMargin = (3.5f * density).toInt()
                    // a／l：0.15 宽格、键面 2/3 贴内侧（源包 bounds），
                    // 缩进 0.05 屏宽（a 左缩／l 右缩）。视图侧无键 id，
                    // 按显示文本认键（无皮肤时布局即内置，大写字母唯一）。
                    if (child is TextKeyView) {
                        when (child.mainText.text.toString()) {
                            "A" -> child.def.skinLeftInsetDp = indentDp
                            "L" -> child.def.skinRightInsetDp = indentDp
                        }
                    }
                    child.refreshBackground()
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
        momentaryShiftActive = false
        updateKeyTextForState(capsState)
        applyCapsColors()
    }

    override fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean) {
        super.updateEditorInfo(info, empty, isComposing)
        val field = EnterFieldBoundary.fromEditorInfo(info)
        lastReturnOutcome = EnterDecision.decide(
            composing = isComposing,
            field = field,
            hasDedicatedNewlineKey = hasDedicatedNewlineKey,
            isLongPress = false,
        )
        refreshReturnKeyStyling()
    }

    /** 绿预设回车分色是否生效：无皮肤快照且当前内置主题为绿预设（26键·浅／深）。 */
    private fun isGreenReturnTheme(): Boolean {
        if (SkinSnapshotProvider.peek() != null) return false
        val themeId = KeyboardColors.themeFor(context).id
        return themeId == KeyboardThemePresets.WeChatGreenLight.id ||
            themeId == KeyboardThemePresets.WeChatGreenDark.id
    }

    /**
     * 回车键绿/灰分色（2026-11-06 施工单 3：源包 enterButton）：
     * 仅绿预设（无皮肤）时按 $returnKeyType 分色——绿档（GO/NEXT/
     * SEARCH/SEND/DONE）用强调底白字，其余（PREVIOUS／组字中／换行）
     * 用功能键底黑字；PREVIOUS 文案取源包灰档默认“回车”（labelFor
     * 的“上一步”只用于图标回车）；其余主题／皮肤生效时恢复出厂
     * （变体色＋默认文案色，不设覆写）。大写态改色后由 applyCapsColors
     * 末尾重调一次，避免文案色被复位。
     */
    private fun refreshReturnKeyStyling() {
        val view = returnKeyView ?: return
        val outcome = lastReturnOutcome ?: return
        if (!isGreenReturnTheme()) {
            if (view.backgroundOverride != null) {
                view.backgroundOverride = null
                view.refreshBackground()
            }
            view.mainText.setTextColor(defaultMainTextColor(view.def))
            return
        }
        val action = (outcome as? EnterOutcome.PerformAction)?.action
        if (isGreenReturnAction(action)) {
            view.backgroundOverride = colors.accentKeyBackground
            view.mainText.setTextColor(colors.accentKeyText)
        } else {
            view.backgroundOverride = colors.specialKeyBackground
            view.mainText.setTextColor(colors.specialKeyText)
            if (action == EnterFieldAction.PREVIOUS) {
                view.updateText("回车")
            }
        }
        view.refreshBackground()
    }

    override fun name(): String {
        return NAME
    }

    private fun switchCapsState(target: CapsState? = null) {
        capsState = target ?: when (capsState) {
            CapsState.None -> CapsState.Once
            CapsState.Once -> CapsState.Lock
            CapsState.Lock -> CapsState.None
            CapsState.Momentary -> CapsState.Momentary
        }
        updateKeyTextForState(capsState)
        applyCapsColors()
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
                // 按住上档与点按一次同图标（均为临时大写）。
                CapsState.Once, CapsState.Momentary -> R.drawable.ic_keyboard_capslock_once
                CapsState.Lock -> R.drawable.ic_keyboard_capslock_lock
            }
        )
    }

    /** 大写激活口径：None 之外（含 Momentary）均视为大写。 */
    override val isUppercased: Boolean get() = capsState != CapsState.None

    override fun onMomentaryShiftDown() {
        if (previewMode) return
        val target = momentaryDownTarget(capsState)
        momentaryShiftActive = target != null
        if (target != null) switchCapsState(target)
    }

    override fun onMomentaryShiftUp() {
        if (previewMode) return
        val target = momentaryUpTarget(capsState, momentaryShiftActive)
        momentaryShiftActive = false
        if (target != null) switchCapsState(target)
    }

    /**
     * 大写前景色（审计 E：uppercased/caps-locked 样式）：遍历全部
     * 文字键主文字——Lock 取 capsLocked（缺省退 uppercased），
     * Once/Momentary 取 uppercased，None 恢复默认色；图片键无
     * 主文字自然跳过。无样式时全程默认色（行为与旧一致）。
     */
    /** 大写态前景色（§5 序：锁定＞大写＞基础，基础由调用方回落默认色）。 */
    override fun capsForegroundColor(appearance: KeyDef.Appearance): Int? =
        StyleStatePriority.foregroundColor(
            swipeUpColor = null,
            swipeDownColor = null,
            capsLockedColor = appearance.skinCapsLockedTextColor,
            uppercasedColor = appearance.skinUppercasedTextColor,
            swipeDir = StyleStatePriority.SwipeDir.NONE,
            isCapsLocked = capsState == CapsState.Lock,
            isUppercased = capsState != CapsState.None,
        )

    /** 大写态前景字号（S6，与 capsForegroundColor 同序；缺省 null = 主字号）。 */
    override fun capsForegroundSize(appearance: KeyDef.Appearance): Float? =
        if (capsState == CapsState.Lock) {
            appearance.skinCapsLockedTextSize ?: appearance.skinUppercasedTextSize
        } else if (capsState != CapsState.None) {
            appearance.skinUppercasedTextSize
        } else {
            null
        }

    /** 大写前景色：遍历全部文字键主文字，取当前大写态色（无则默认色）。 */
    private fun applyCapsColors() {
        for (row in keyRows) {
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i) as? KeyView ?: continue
                val appearance = child.def
                val color = capsForegroundColor(appearance)
                // S6：大写态字号同序（无则跟主字号，仍空不动）。
                val size = capsForegroundSize(appearance) ?: appearance.skinTextSize
                when (child) {
                    is TextKeyView -> {
                        child.mainText.setTextColor(
                            color ?: defaultMainTextColor(appearance)
                        )
                        if (size != null) {
                            child.mainText.setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_DIP, size,
                            )
                        }
                    }
                    is AltTextKeyView -> {
                        child.mainText.setTextColor(
                            color ?: defaultMainTextColor(appearance)
                        )
                        if (size != null) {
                            child.mainText.setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_DIP, size,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        // 回车分色：大写态改色会复位回车文案色，按当前编辑框动作重调一次。
        refreshReturnKeyStyling()
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        punctuationState = mode
        super.updatePunctuationMode(mode)
    }
}
