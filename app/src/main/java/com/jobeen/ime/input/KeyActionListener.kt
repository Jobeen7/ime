package com.jobeen.ime.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.IEngine
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.skin.diag.DiagLog

class KeyActionListener(
    private val service: InputMethodService,
) : KeyActionListener {

    private val engine: IEngine? get() = EngineFactory.current()

    override fun onKeyAction(action: KeyboardAction) {
        // 接线轮 R1：键盘动作事件喂给通知状态（锁定变化时窗口
        // 经回调以 post 重建键盘，不在分发中途同步重建）。
        com.jobeen.ime.skin.runtime.SkinRuntimeState.onEvent(
            com.jobeen.ime.skin.notify.NotificationEvent(
                com.jobeen.ime.skin.notify.NotificationSource.KEYBOARD_ACTION,
                com.jobeen.ime.skin.notify.NotificationActionNames.nameOf(action),
                null,
            ),
        )
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
                commitOrPair(action.text)
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

            // ShowInputMethodPickerAction 不在此处理：它在
            // KeyboardWindowView.transformed 里被拦截（弹方案选择框）
            // 并返回 null，永远到不了本监听——原先这里的分支不可达，
            // 已删除，避免误以为请求系统输入法选择器是生效路径

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

    /**
     * 按键提交：符号成对（2026-10-28 截图 6 项）只在此分支判定，
     * LiteralCommitAction（皮肤字面提交）不受影响、保持字面精确。
     * 门：开关开 + 非组字 + 非密码框 + 单开符号 + 引号前文守卫。
     * 前文只在引号类且前门全过时读一次（跨进程 Binder 调用，括号
     * 类不读）。成对路径抛错时回退普通提交，永不丢字；异常只记
     * 元数据（操作 + 阶段 + 异常类名），不记提交文本。
     */
    private fun commitOrPair(text: String) {
        val e = engine ?: return
        val enabled = KeyboardManager.Keyboard.SymbolPairing.isEnabled(service)
        val composing = KeyboardStateManager.isComposingNow
        val password = isPasswordField()
        val beforeCursor: String? =
            if (text.length == 1 && PairingPolicy.isQuote(text[0]) &&
                enabled && !composing && !password
            ) {
                try {
                    service.currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
                } catch (ex: Exception) {
                    null
                }
            } else {
                null
            }
        if (!PairingPolicy.shouldPair(text, enabled, composing, password, beforeCursor)) {
            e.commit(text)
            return
        }
        val pair = PairingPolicy.pairText(text[0]) ?: run {
            e.commit(text)
            return
        }
        try {
            e.commitPair(pair, text)
        } catch (ex: Exception) {
            DiagLog.e(
                "SkinDiag", "pair_commit_failed",
                mapOf("stage" to "commit_pair", "error" to ex.javaClass.simpleName),
                ex,
            )
            e.commit(text)
        }
    }

    /** 密码框判定（与 KeyboardStateManager.startInput 同口径）：文本类 + 三种密码 variation。 */
    private fun isPasswordField(): Boolean {
        val info = service.currentInputEditorInfo ?: return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }
}
