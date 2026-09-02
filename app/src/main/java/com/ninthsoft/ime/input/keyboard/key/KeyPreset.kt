package com.ninthsoft.ime.input.keyboard.key

import android.graphics.Typeface
import android.graphics.Typeface.BOLD
import android.view.KeyEvent
import com.ninthsoft.ime.R
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant

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
    ), popups = arrayOf(
        KeyDef.Popup.Preview(character)
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


fun backspaceKey(): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_backspace,
        viewId = KeyView.button_backspace,
        percentWidth = 0.15f,
        variant = Variant.Alternative,
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.BackspaceAction),
        KeyDef.Behavior.Repeat(KeyboardAction.BackspaceAction),
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
        displayText = displayText, textSize = 15f,
        textStyle = Typeface.BOLD,
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
        KeyDef.Behavior.LongPress(KeyboardAction.VoiceInputAction),
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

fun returnKey(percentWidth: Float): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.Image(
        src = R.drawable.ic_keyboard_return,
        viewId = KeyView.button_return,
        percentWidth = percentWidth,
        variant = Variant.Accent,
        border = Border.Special,
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


fun zeroKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
    appearance = KeyDef.Appearance.AltText(
        displayText = "@",
        altText = "0",
        textSize = 15f,
        percentWidth = percentWidth,
        mainTextTranslationY = 3,
        altTextTranslationY = 4,
        variant = Variant.Alternative
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyboardAction.CommitAction("0")),
        KeyDef.Behavior.LongPress(KeyboardAction.CommitAction("@")),
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
        textSize = 22f,
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
