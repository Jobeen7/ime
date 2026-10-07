package com.jobeen.ime.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.IEngine
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.ImeInputMethodService

class KeyActionListener(
    private val service: InputMethodService,
) : KeyActionListener {

    private val engine: IEngine? get() = EngineFactory.current()

    override fun onKeyAction(action: KeyboardAction) {
        when (action) {
            is KeyboardAction.KeySequenceAction -> {
                engine?.processKey(service, action.asKeyEvent())
            }

            is KeyboardAction.KeyCodeAction -> {
                engine?.processKey(service, action.asKeyEvent())
            }

            is KeyboardAction.ClearAction -> {
                engine?.clear(service)
            }

            is KeyboardAction.CommitAction -> {
                engine?.commit(action.text)
            }

            is KeyboardAction.BackspaceAction -> {
                // 剪贴板搜索态：退格先问面板（删查询/吞掉），不归引擎才删拼音或文档
                val intercepted = (service as? ImeInputMethodService)?.keyboardWindow
                    ?.panel?.handleClipSearchBackspace(
                        com.jobeen.ime.input.keyboard.window.KeyboardStateManager.isComposingNow
                    ) == true
                if (!intercepted) {
                    engine?.processKey(
                        service, KeyEvent.CodeEvent(
                            KeyEvent.CodeEvent.keyCode("DEL"), KeyModifiers.Empty
                        )
                    )
                }
            }

            is KeyboardAction.ReturnAction, KeyboardAction.SpaceAction -> {
                val character = when (action) {
                    is KeyboardAction.ReturnAction -> "ENTER"
                    KeyboardAction.SpaceAction -> "SPACE"
                }
                engine?.processKey(
                    service, KeyEvent.CodeEvent(
                        KeyEvent.CodeEvent.keyCode(character), KeyModifiers.Empty
                    )
                )
            }

            is KeyboardAction.CursorMoveAction -> {
                engine?.moveCursor(service, action.direction)
            }

            is KeyboardAction.LangSwitchAction -> {
                @SuppressLint("NewApi") service.switchToNextInputMethod(false)
            }

            is KeyboardAction.SelectSchema -> {
                engine?.selectSchema(action.schemaId)
            }

            is KeyboardAction.ShowInputMethodPickerAction -> {
                @SuppressLint("NewApi") service.requestShowSelf(0)
            }

            is KeyboardAction.SelectCandidatePinYin -> {
                engine?.selectCandidatePinYin(action.pinYin)
            }

            is KeyboardAction.UndoAction -> {
                engine?.undo(service)
            }

            is KeyboardAction.RedoAction -> {
                engine?.redo(service)
            }

            is KeyboardAction.MultiReturnAction -> {
                engine?.resetComposition()

                val ic = (service as ImeInputMethodService).activeInputConnection() ?: return
                ic.performEditorAction(
                    when (action.text) {
                        "GO" -> EditorInfo.IME_ACTION_GO
                        "SEND" -> EditorInfo.IME_ACTION_SEND
                        "SEARCH" -> EditorInfo.IME_ACTION_SEARCH
                        "NEXT" -> EditorInfo.IME_ACTION_NEXT
                        "PREVIOUS" -> EditorInfo.IME_ACTION_PREVIOUS
                        "DONE" -> EditorInfo.IME_ACTION_DONE
                        else -> EditorInfo.IME_ACTION_NONE
                    }
                )
            }


            else -> {}
        }
    }
}
