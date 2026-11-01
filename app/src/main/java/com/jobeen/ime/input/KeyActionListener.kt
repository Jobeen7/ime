package com.jobeen.ime.input

import android.annotation.SuppressLint
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.IEngine
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SnippetExpander
import com.jobeen.ime.skin.script.SkinScriptActivity
import com.jobeen.ime.skin.script.SkinScriptKeyBridge
import com.jobeen.ime.skin.script.SkinScriptRunner
import com.jobeen.ime.skin.script.SkinScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyActionListener(
    private val service: InputMethodService,
) : KeyActionListener {

    private val engine: IEngine? get() = EngineFactory.current()

    /**
     * 按键脚本执行器（惰性构造，只包文件目录引用，执行前无 I/O）：
     * 与管理页用同一脚本库（service.filesDir 下同一目录），故
     * 管理页的添加／开关即时对按键生效，无需同步。
     */
    private val scriptRunner: SkinScriptRunner by lazy {
        SkinScriptRunner(SkinScriptStore(service.filesDir))
    }

    override fun onKeyAction(action: KeyboardAction) {
        // P4 分段探针（分发段）：起点标记；门控关时 stageStart 返回 -1，
        // 末尾 noteStage 即 no-op。早返回路径（MultiReturnAction 取不到
        // 输入连接）不产生样本——该路径本就不走到引擎分发。
        val dispatchStart = com.jobeen.ime.skin.perf.PerfMonitor.stageStart()
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

            // 向前删除（deleteForward，Wave-4 遗留缺口补齐）：
            // 引擎侧 ForwardDelete 全语义（组字删编码右侧、选区／
            // 光标右侧整体删、右侧无物空操作），本分支只做接应。
            is KeyboardAction.DeleteForwardAction -> {
                engine?.deleteForward(service)
            }

            // 删除选中文本（#deleteText）：仅删当前选区；无选区
            // 空操作，绝不回退为退格；组字中禁行（选区与组字
            // 重叠，连接侧删除会损坏组字态，见 DeleteTextPolicy）。
            // 读选区与提交皆经当前输入连接（与剪贴板动作同口径，
            // 不走短语桥虚拟连接）；连接异常只记元数据，不崩溃。
            is KeyboardAction.DeleteTextAction -> {
                try {
                    val ic = service.currentInputConnection
                    val selected = runCatching { ic?.getSelectedText(0) }.getOrNull()
                    if (ic != null && DeleteTextPolicy.shouldDelete(
                            KeyboardStateManager.isComposingNow, selected,
                        )
                    ) {
                        ic.commitText("", 1)
                    }
                } catch (ex: Exception) {
                    DiagLog.w(
                        "keymap", "delete_text_failed",
                        mapOf("error" to ex.javaClass.simpleName),
                    )
                }
            }

            // RIME 选项翻转（rimeOption）：引擎侧异步读现值取反；
            // 三态以上选项不适用，不持久化（见 SkinAction 文档）。
            is KeyboardAction.ToggleRimeOptionAction -> {
                engine?.toggleRuntimeOption(action.option)
            }

            // 模板文本上屏（snippet）：日期展开后字面提交；光标位
            // 在全文上屏后按标记后单元数向左移（DPAD 步进）。
            // 顺序残余：上屏与移光标走引擎两条通道（actions/jobs），
            // 正常路径上屏先行；极端交错下光标位需真机复核。
            is KeyboardAction.SnippetAction -> {
                val expanded = SnippetExpander.expand(action.template)
                engine?.commit(expanded.text)
                repeat(expanded.cursorUnitsFromEnd) {
                    engine?.moveCursor(service, -1)
                }
            }

            // 子动作间停顿（delay，combine 专用）：分发线程有界
            // 阻塞；毫秒上限解析期已钳（≤1000ms），此处不二次钳制。
            is KeyboardAction.DelayAction -> {
                if (action.ms > 0) {
                    try {
                        Thread.sleep(action.ms.toLong())
                    } catch (ex: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
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

            // 中英切换（纠正 2，2026-11-01 定）：#中英切换 =
            // RIME ascii_mode 翻转，与菜单 ascii 开关同款逻辑——
            // 只翻偏好（引擎侧经偏好应用跟随），绝不切键盘类型。
            // 布局切换是另一机制（keyboardType 动作，见
            // SwitchKeyboardTypeAction），两者并存、互不替代。
            is KeyboardAction.ToggleAsciiModeAction -> {
                CandidateManager.setAsciiModeEnabled(
                    service,
                    !CandidateManager.isAsciiModeEnabled(service),
                )
            }

            // 键盘类型切换（纠正 2 新增，与中英切换并存的独立
            // 机制）：皮肤绑定 keyboardType 时切类型（含自定义名），
            // 经类型状态入口（含内置回退，永不空白）；窗口层
            // transformed 直通至此，不拦截。
            is KeyboardAction.SwitchKeyboardTypeAction -> {
                KeyboardStateManager.switchKeyboardType(action.type)
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

            // 宿主文本撤销（#undo）：经当前输入连接的上下文菜单
            // 动作，尽力而为（返回值不作成功判定）；宿主不支持
            // 时无效果，不回退删字；连接为空不崩溃。
            is KeyboardAction.HostUndoAction -> {
                try {
                    service.currentInputConnection?.performContextMenuAction(
                        android.R.id.undo,
                    )
                } catch (ex: Exception) {
                    DiagLog.w(
                        "keymap", "host_undo_failed",
                        mapOf("error" to ex.javaClass.simpleName),
                    )
                }
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

            // 字面提交（审计 E：symbol 通道与 commit_direct）：经
            // 引擎直接上屏，不经组字、不经标点转换、不成对。
            // 注意：此前本分支缺失，LiteralCommitAction 落入 else
            // 被静默吞掉（Qwerty 变换层只保证了“原样下发”，分发
            // 层并无接应）；本分支补齐该通道，commit_direct 键与
            // 符号键自此才真正上屏。
            is KeyboardAction.LiteralCommitAction -> {
                engine?.commit(action.text)
            }

            // 组合按键序列（审计 E：sendKeys）：逐个经引擎按键通道。
            is KeyboardAction.SendKeysAction -> {
                for (combo in action.keys) {
                    engine?.processKey(
                        service,
                        KeyEvent.CodeEvent(combo.keyCode, combo.modifiers, isVirtual = true),
                    )
                }
            }

            // 直送应用的按键（审计 E：sendKeyEvent / tab / escape /
            // 行首行尾）：绕过引擎，经输入连接直发应用；对端全
            // 语义前置：先清组字（不提交）、先关联想；焦点在
            // 本键盘自有输入框（当前字段属本包）时空操作；坏键名
            // 解析期已剔除（VALUE_INVALID），到此恒为合法键码。
            // 顺序残余：清组字／关联想走引擎通道、按键直发同步，
            // 正常路径前置先行；极端交错下需真机复核。
            is KeyboardAction.AppKeyEventAction -> {
                val ownField =
                    service.currentInputEditorInfo?.packageName == service.packageName
                if (ownField) {
                    DiagLog.w("keymap", "send_key_event_own_field", emptyMap())
                } else {
                    engine?.resetComposition()
                    engine?.dismissPredictions()
                    com.jobeen.ime.base.util.InputConnectionUtil.sendCombinationKeyEvent(
                        service, action.keyCode,
                        ctrl = action.ctrl, shift = action.shift,
                        alt = action.alt, meta = action.meta,
                    )
                }
            }

            // 组合动作（审计 E：combine）：直接分发路径按序递归；
            // 窗口路径由窗口包装先展开（子动作各经 transformed），
            // 不会到达此处。
            is KeyboardAction.CombineAction -> {
                for (sub in action.actions) onKeyAction(sub)
            }

            // 打开链接（审计 E：openURL）：白名单外一律丢弃记日志。
            is KeyboardAction.OpenUrlAction -> {
                if (!OpenUrlPolicy.isAllowed(action.url)) {
                    DiagLog.w(
                        "keymap", "open_url_blocked",
                        mapOf("scheme" to action.url.substringBefore(":", "")),
                    )
                } else {
                    try {
                        service.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(action.url),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (ex: Exception) {
                        DiagLog.w(
                            "keymap", "open_url_failed",
                            mapOf("error" to ex.javaClass.simpleName),
                        )
                    }
                }
            }

            // 刻意空操作（审计 E：none）：显式吞掉，不回退出厂行为。
            is KeyboardAction.NoOpAction -> Unit

            // 瞬时上档（审计 E：shift）：直接分发无按下/抬起上下文，
            // 恒为空操作；真正语义只在按键面按下/抬起钩子实现。
            is KeyboardAction.MomentaryShiftAction -> Unit

            // 打开本应用设置（审计 E：settings）：与面板设置同款意图。
            is KeyboardAction.SettingsAction -> {
                service.startActivity(
                    android.content.Intent(
                        service, com.jobeen.ime.ui.MainActivity::class.java,
                    ).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    },
                )
            }

            // 打开系统设置（审计 E：systemSettings）。
            is KeyboardAction.SystemSettingsAction -> {
                service.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_SETTINGS,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            // 隐藏键盘（审计 E：dismissKeyboard）：窗口路径由
            // transformed 拦截；此处接应无窗口的直接分发路径。
            is KeyboardAction.HideKeyboardAction -> {
                service.requestHideSelf(0)
            }

            // 回主键盘／回上一键盘（审计 E：returnPrimary/Last）：
            // 经类型状态切换，窗口路径直通至此（transformed 不拦截）。
            is KeyboardAction.ReturnPrimaryAction -> {
                KeyboardStateManager.returnPrimaryKeyboard()
            }

            is KeyboardAction.ReturnLastAction -> {
                KeyboardStateManager.returnLastKeyboard()
            }

            // 剪贴板（审计 E：#cut/copy/paste/clearSystemPasteboard）：
            // 经输入连接（cut/copy/paste）或系统剪贴板（clear）。
            is KeyboardAction.ClipboardAction -> {
                when (action.kind) {
                    KeyboardAction.ClipboardAction.Kind.CUT ->
                        service.currentInputConnection?.performContextMenuAction(
                            android.R.id.cut,
                        )
                    KeyboardAction.ClipboardAction.Kind.COPY ->
                        service.currentInputConnection?.performContextMenuAction(
                            android.R.id.copy,
                        )
                    KeyboardAction.ClipboardAction.Kind.PASTE ->
                        service.currentInputConnection?.performContextMenuAction(
                            android.R.id.paste,
                        )
                    KeyboardAction.ClipboardAction.Kind.CLEAR -> {
                        val clipboard = service.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE,
                        ) as? android.content.ClipboardManager
                        if (clipboard != null) {
                            if (android.os.Build.VERSION.SDK_INT >=
                                android.os.Build.VERSION_CODES.P
                            ) {
                                clipboard.clearPrimaryClip()
                            } else {
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("", ""),
                                )
                            }
                        }
                    }
                }
            }

            // 简繁切换（审计 E：#简繁切换）：翻转繁体偏好。
            is KeyboardAction.ToggleTraditionalAction -> {
                CandidateManager.setTraditionalChineseEnabled(
                    service,
                    !CandidateManager.isTraditionalChineseEnabled(service),
                )
            }

            // 内嵌输入切换（审计 E：#toggleEmbeddedInputMode）。
            is KeyboardAction.ToggleEmbeddedInputAction -> {
                CandidateManager.setInlineInputModeEnabled(
                    service,
                    !CandidateManager.isInlineInputModeEnabled(service),
                )
            }

            // 候选序号选择（审计 E：#次选/#三选）：窗口路径凭上一帧
            // 候选表解析；直接分发无候选表，丢弃（transformed 已拦截）。
            is KeyboardAction.SelectCandidateIndexAction -> Unit

            // 打开脚本管理页（open_script）／切换脚本视图
            // （#toggleScriptView）：两者同目的地、不同动作种类；
            // 切换的收起方向由管理页返回键承担（见动作注释），
            // 故分发层同作打开处理。服务上下文起 Activity 必须
            // 带 NEW_TASK（管理页入口本身是 Activity 上下文入口，
            // 不带该标志；此处显式补齐，不改入口本身的任务语义）。
            is KeyboardAction.OpenScriptsAction,
            is KeyboardAction.ToggleScriptViewAction -> {
                service.startActivity(
                    SkinScriptActivity.intent(service)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            // 运行应用级脚本（run_script）：执行走后台（Runner 预算
            // 最高 500ms，主线程执行会卡 UI）；返回值转成的动作回
            // 主线程再分发（与 combine 递归分发同口径）；无动作时
            // 只记脚本 id 与原因元数据，绝不记返回值与脚本日志行。
            is KeyboardAction.RunScriptAction -> {
                appScope.launch(Dispatchers.Default) {
                    when (val outcome =
                        SkinScriptKeyBridge.runForKey(scriptRunner, action.scriptId)
                    ) {
                        is SkinScriptKeyBridge.KeyRunOutcome.Dispatch -> {
                            withContext(Dispatchers.Main) {
                                onKeyAction(outcome.action)
                            }
                        }
                        is SkinScriptKeyBridge.KeyRunOutcome.NoAction -> {
                            DiagLog.w(
                                "keymap", "script_key_no_action",
                                mapOf(
                                    "scriptId" to action.scriptId,
                                    "reason" to outcome.reason.name.lowercase(),
                                ),
                            )
                        }
                    }
                }
            }

            // 常用语／候选栏切换（审计 E：#showPhraseView /
            // #candidatesBarStateToggle）：窗口路径拦截；直接分发
            // 无窗口/面板句柄，丢弃。
            is KeyboardAction.ShowPhraseViewAction,
            is KeyboardAction.CandidatesBarToggleAction -> Unit

            else -> {}
        }
        com.jobeen.ime.skin.perf.PerfMonitor.noteStage(
            com.jobeen.ime.skin.perf.PerfMonitor.Event.KEY_DISPATCH, dispatchStart,
        )
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
