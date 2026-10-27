package com.jobeen.ime.input.keyboard.key

import android.graphics.Typeface
import android.graphics.Typeface.BOLD
import android.view.KeyEvent
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant

fun alphabetKey(
    character: String,
    punctuation: String,
    altTextTranslationY: Int = 2,
    mainTextTranslationY: Int = -2,
) = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = character,
        altText = punctuation,
        textSize = 22f,
        altTextTranslationY = altTextTranslationY,
        mainTextTranslationY = mainTextTranslationY,
        variant = Variant.Normal
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(character)),
        KeyDef.Behavior.LongPress(KeyboardAction.CommitAction(punctuation))
    )
)

fun mixedAlphabetKey(digit: String, letters: String, percentWidth: Float = 0.23333f) = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = letters,
        altText = digit,
        textSize = 18f,
        percentWidth = percentWidth,
        mainTextTranslationY = 4,
        altTextTranslationY = 4
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(digit)),
        KeyDef.Behavior.LongPress(KeyboardAction.CommitAction(digit))
    ),
)

fun commitKey(
    character: String, percentWidth: Float = 0.23333f,
    variant: Variant = Variant.Normal,
    fontSize: Float = 20f,
) = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = character, textSize = fontSize, variant = variant, percentWidth = percentWidth
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CommitAction(character)),
    )
)

fun sidePannelKey(percentWidth: Float = 0.15f, rowSpan: Int = 3, visableRow: Int = 4) = KeyDef(
    appearance = KeyDef.Appearance.SidePannel(
        rowSpan = rowSpan,
        visableRow = visableRow,
        percentWidth = percentWidth,
        variant = Variant.Alternative
    ),
    behaviors = setOf(),
)

fun sidePannelNormalItem(character: String, percentWidth: Float = 0.15f) = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = character,
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative
    ),
    behaviors = setOf(),
)

fun capsLockKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_capslock_none,
        viewId = KeyView.button_caps,
        percentWidth = 0.15f,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CapsAction),
    ),
)


fun backspaceKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_backspace,
        viewId = KeyView.button_backspace,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.BackspaceAction),
        KeyDef.Behavior.Repeat(KeyboardAction.BackspaceAction),
        KeyDef.Behavior.Swipe(
            upAction = KeyboardAction.UndoAction,
            downAction = KeyboardAction.RedoAction,
        ),
    ),
)

fun layoutSwitchKey(
    displayText: String,
    target: String,
    percentWidth: Float = 0.15f,
): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = displayText,
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.LayoutSwitchAction(target)),
        KeyDef.Behavior.LongPress(KeyboardAction.KeySequenceAction("/"))
    ),
)


fun resumeLayoutKey(
    displayText: String,
    percentWidth: Float = 0.15f,
): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = displayText,
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ResumeAction)),
)

fun spaceKey(percentWidth: Float = 0.23333f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "",
        textSize = 13f,
        percentWidth = percentWidth,
        border = Border.Special,
        viewId = KeyView.button_space,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.SpaceAction),
        KeyDef.Behavior.LongPress(KeyboardAction.VoiceInputAction()),
    ),
)


fun peroidKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.ImageText(
        displayText = ".",
        textSize = 18f,
        src = R.drawable.ic_keyboard_symbol,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        viewId = KeyView.button_peroid
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(".")),
        KeyDef.Behavior.LongPress(KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME))
    ),
)


fun schemaSwitchKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_language,
        viewId = KeyView.button_lang,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.RotateSchema),
        KeyDef.Behavior.LongPress(KeyboardAction.ShowInputMethodPickerAction),
    ),
)

fun symbolSwitchKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_symbol,
        viewId = KeyView.button_lang,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME))),
)

/**
 * 回车键变体按主题取（2026-10-27 澄清的原意）：仅素白（light）
 * 与暗夜（amoled）跟随普通键；其余主题（含落日、莫奈、自定义
 * 主题）与未知 id 一律取 Accent（上游行为：落日橙色、莫奈主色）。
 * 纯逻辑：不读偏好、不碰 Android 框架，可直接单测。
 */
fun returnKeyVariantForTheme(themeId: String): Variant =
    if (themeId == KeyboardTheme.LIGHT_DEFAULT_ID ||
        themeId == KeyboardTheme.DARK_DEFAULT_ID
    ) {
        Variant.Normal
    } else {
        Variant.Accent
    }

fun returnKey(
    percentWidth: Float,
    rowSpan: Int = 1,
    variant: Variant = Variant.Accent,
): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_return,
        viewId = KeyView.button_return,
        percentWidth = percentWidth,
        // 变体由调用方按当前主题传入（returnKeyVariantForTheme）：
        // 素白/暗夜取 Normal（常态/按压/描边/图标染色全部随普通键
        // 口径），其余主题取 Accent。缺省 Accent 即上游行为。
        // Accent 变体本身保留在主题模型里（方案弹窗等其他 accent
        // 用途不动），这里只改回车的取法
        variant = variant,
        border = Border.Special,
        rowSpan = rowSpan,
        alignSpanEnd = rowSpan > 1,
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.ReturnAction()),
        KeyDef.Behavior.LongPress(KeyboardAction.ReturnAction(true)),
    )
)

fun prevPageKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_arrow_up,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        border = Border.On,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ReturnAction())),
)

fun nextPageKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_arrow_down,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        border = Border.On,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ReturnAction())),
)

fun segmentKey(percentWidth: Float = 0.23333f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = "分词",
        altText = "1",
        textSize = 16f,
        percentWidth = percentWidth,
        mainTextTranslationY = 4,
        altTextTranslationY = 3
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(
            KeyboardAction.KeyCodeAction(KeyEvent.KEYCODE_APOSTROPHE)
        ), KeyDef.Behavior.LongPress(KeyboardAction.CommitAction("1"))
    ),
)

fun clearKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "清空",
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.ClearAction)
    ),
)

fun newlineKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "换行",
        textSize = 15f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CommitAction("\n"))
    ),
)


fun zeroKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "@",
        textSize = 18f,
        percentWidth = percentWidth,
        variant = Variant.Alternative
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CommitAction("@")),
        KeyDef.Behavior.LongPress(KeyboardAction.CommitAction("0")),
    ),
)

fun infiniteKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.ImageText(
        displayText = "0",
        textSize = 11f,
        src = R.drawable.ic_keyboard_infinite,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CommitAction("0")),
        KeyDef.Behavior.LongPress(KeyboardAction.CommitAction("∞")),
    ),
)

fun miniSpaceKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_space,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.SpaceAction)),
)

fun atKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = "@",
        textSize = 18f,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CommitAction("@")),
    )
)

fun textKey(
    character: String, percentWidth: Float = 0.23333f,
    variant: Variant = Variant.Normal,
    fontSize: Float = 20f,
) = KeyDef(
    appearance = KeyDef.Appearance.Text(
        displayText = character, textSize = fontSize, variant = variant, percentWidth = percentWidth
    ), behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(character)),
    ), popups = arrayOf(
        KeyDef.Popup.Preview(character)
    )
)
