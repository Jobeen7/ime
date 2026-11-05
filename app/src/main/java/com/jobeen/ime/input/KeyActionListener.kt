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
import com.jobeen.ime.R
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.base.speech.VoiceUndoLedger
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SnippetExpander
import com.jobeen.ime.skin.script.SkinScriptActivity
import com.jobeen.ime.skin.script.SkinScriptKeyBridge
import com.jobeen.ime.skin.script.SkinScriptRunner
import com.jobeen.ime.skin.script.SkinScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * 同脚本在途运行（单飞，取消上一运行）：同一 scriptId 再按
     * 下时，先取消上一任务再起新任务。取消只拦得住尚未切回主
     * 线程的分发（withContext 抛取消异常，动作不分发）；Rhino
     * 求值本身不可中断，在途求值仍会在后台跑完，但其分发已被
     * 取消、不会落地。串行排队（等上一运行跑完再跑下一运行）
     * 会把旧结果也分发一次，重复动作更糟，故不取串行。完成
     * 的任务经 invokeOnCompletion 自清，避免表无限增长。
     */
    private val scriptJobs = ConcurrentHashMap<String, Job>()

    override fun onKeyAction(action: KeyboardAction) {
        // P4 分段探针（分发段）：起点标记；门控关时 stageStart 返回 -1，
        // 末尾 noteStage 即 no-op。早返回路径（MultiReturnAction 取不到
        // 输入连接）不产生样本——该路径本就不走到引擎分发。
        val dispatchStart = com.jobeen.ime.skin.perf.PerfMonitor.stageStart()
        // 接线轮 R1：键盘动作事件喂给通知状态（锁定变化时窗口
        // 经回调以 post 重建键盘，不在分发中途同步重建）。
        // A5：事件带区分值（切方案／切类型／rime 选项取参数值，
        // 其余动作空），通知可按参数值匹配，不只按种类。
        com.jobeen.ime.skin.runtime.SkinRuntimeState.onEvent(
            com.jobeen.ime.skin.notify.NotificationEvent(
                com.jobeen.ime.skin.notify.NotificationSource.KEYBOARD_ACTION,
                com.jobeen.ime.skin.notify.NotificationActionNames.nameOf(action),
                com.jobeen.ime.skin.notify.NotificationActionNames.valueOf(action),
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
            //
            // 清字入账：删除成功即把「被清掉的这段」连同删除点前的
            // 锚点记进 ClearedTextLedger，下滑撤销据此原样送回，不再
            // 依赖 Ctrl+Z——污染会话内 Ctrl+Z 已被拦（防清掉的组字
            // 复活），不记账的话清掉的已上屏文字就没有任何地方可回。
            // 锚点必须在删除**之前**现读：删完光标已落在删除点，
            // 那时读到的前文才是恢复时要核对的位置。
            is KeyboardAction.DeleteTextAction -> {
                try {
                    val ic = service.currentInputConnection
                    val selected = runCatching { ic?.getSelectedText(0) }.getOrNull()
                    if (ic != null && DeleteTextPolicy.shouldDelete(
                            KeyboardStateManager.isComposingNow, selected,
                        )
                    ) {
                        // 有选区时 getTextBeforeCursor 取的是选区之前的
                        // 文本（与 VoiceUndoLedger 同口径），正是删除点
                        // 紧邻的前文；取失败按空锚点记（恢复走严判）。
                        val anchor = runCatching {
                            ic.getTextBeforeCursor(ClearedTextLedger.ANCHOR_CHARS, 0)?.toString()
                        }.getOrNull().orEmpty()
                        if (ic.commitText("", 1)) {
                            ClearedTextLedger.record(selected?.toString().orEmpty(), anchor)
                        }
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

            // 回车精简决策：软回车键不再经 processKey 的状态键
            // 旧路径（图标变换、force 旁路、空探针均已删）。字段
            // 属性在分发时点由当前编辑框信息映射（框架属性，
            // 无信息时按单行＋未声明动作计）；组字中由引擎串行
            // job 执行时点现读原始输入判定，不在此处预判。
            // 显式绑定的 MultiReturnAction 不走本分支，原样不动。
            is KeyboardAction.ReturnAction -> {
                engine?.processEnter(
                    service,
                    EnterFieldBoundary.fromEditorInfo(service.currentInputEditorInfo),
                    isLongPress = action.force,
                    hasDedicatedNewlineKey = action.hasDedicatedNewlineKey,
                )
            }

            is KeyboardAction.SpaceAction -> {
                engine?.processKey(
                    service, KeyEvent.CodeEvent(
                        KeyEvent.CodeEvent.keyCode("SPACE"), KeyModifiers.Empty
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

            // 宿主文本撤销（#undo）：改道为与下滑 UndoAction 同一
            // 账本路径——先查上滑清字账本（原样写回），再查语音
            // 账本、打字账本（污染会话内才查打字账本，与下滑
            // 一致）；三账本都不命中且会话被污染时空操作，绝不
            // 回退宿主撤销（宿主撤销栈同样可能把清掉的组字复活）；
            // 未污染且三账本都不命中时保持原有行为：经当前输入
            // 连接的上下文菜单动作，尽力而为（返回值不作成功判
            // 定）；宿主不支持时无效果，不回退删字；连接为空不崩溃。
            // 组字中三账本一律不查（与下滑 .17 门一致），污染门
            // 仍按 decideHostUndoFallback 执行。
            // 生产方核查：HostUndoAction 的唯一生产方是皮肤
            // #undo 映射（KeyOverrideApplier.HASH_UNDO），无非皮肤
            // 调用方（工具栏仅作 #undo 文案映射，KeyPreset 下滑用
            // 的是 UndoAction），故改本分支即只改皮肤 #undo 路径，
            // 不存在需要保留的另一条非皮肤宿主撤销路径。
            is KeyboardAction.HostUndoAction -> {
                try {
                    val ic = service.currentInputConnection
                    val composing = KeyboardStateManager.isComposingNow
                    val clearedHit = !composing && ic != null &&
                        ClearedTextLedger.tryRestore(ic)
                    val voiceHit = !composing && !clearedHit && ic != null &&
                        VoiceUndoLedger.tryUndo(ic)
                    val poisoned = TypedUndoLedger.isPoisoned()
                    val typedHit = !composing && !clearedHit && !voiceHit &&
                        poisoned && ic != null && TypedUndoLedger.tryUndo(ic)
                    when (
                        TypedUndoLedger.decideHostUndoFallback(
                            clearedHit = clearedHit,
                            voiceHit = voiceHit,
                            typedHit = typedHit,
                            poisoned = poisoned,
                            composing = composing,
                        )
                    ) {
                        TypedUndoLedger.HostUndoFallback.DONE,
                        TypedUndoLedger.HostUndoFallback.NOOP -> Unit
                        TypedUndoLedger.HostUndoFallback.HOST_UNDO -> {
                            ic?.performContextMenuAction(android.R.id.undo)
                        }
                    }
                } catch (ex: Exception) {
                    DiagLog.w(
                        "keymap", "host_undo_failed",
                        mapOf("error" to ex.javaClass.simpleName),
                    )
                }
            }

            // 宿主文本全选（P0-2：#selectText）：经当前输入连接
            // 的上下文菜单动作，尽力而为（返回值不作成功判定）；
            // 宿主不支持时无效果，不回退删字；连接为空不崩溃。
            is KeyboardAction.SelectAllAction -> {
                try {
                    service.currentInputConnection?.performContextMenuAction(
                        android.R.id.selectAll,
                    )
                } catch (ex: Exception) {
                    DiagLog.w(
                        "keymap", "select_all_failed",
                        mapOf("error" to ex.javaClass.simpleName),
                    )
                }
            }

            // 宿主文本重做（#redo）：与 #undo 同口径，见上。
            is KeyboardAction.HostRedoAction -> {
                try {
                    service.currentInputConnection?.performContextMenuAction(
                        android.R.id.redo,
                    )
                } catch (ex: Exception) {
                    DiagLog.w(
                        "keymap", "host_redo_failed",
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
            // 白名单内先弹确认（与部署确认同款 IMS showDialog 范
            // 式：挂键盘窗口 token 的确认框，用户看清链接再决定）；
            // 确认才起意图，取消只记 scheme 元数据。无安全确认面
            // 时（服务非 IMS、或键盘窗口未挂上无 token）不硬造假
            // 确认：退回改前直开行为（长度上限仍由白名单守），并
            // 记一条元数据日志——该路径的确认缺口如实列入残余。
            // 日志恒只记 scheme，不记完整链接。
            is KeyboardAction.OpenUrlAction -> {
                if (!OpenUrlPolicy.isAllowed(action.url)) {
                    DiagLog.w(
                        "keymap", "open_url_blocked",
                        mapOf("scheme" to action.url.substringBefore(":", "")),
                    )
                } else {
                    val imeService = service as? ImeInputMethodService
                    val hasToken = imeService?.keyboardWindow?.view?.windowToken != null
                    if (imeService != null && hasToken) {
                        val dialog = android.app.AlertDialog.Builder(imeService)
                            .setTitle(R.string.open_url_confirm_title)
                            .setMessage(
                                imeService.getString(
                                    R.string.open_url_confirm_message, action.url,
                                ),
                            )
                            .setPositiveButton(R.string.open_url_confirm_open) { _, _ ->
                                openUrl(action.url)
                            }
                            .setNegativeButton(R.string.open_url_confirm_cancel) { _, _ ->
                                DiagLog.w(
                                    "keymap", "open_url_cancelled",
                                    mapOf(
                                        "scheme" to action.url.substringBefore(":", ""),
                                    ),
                                )
                            }
                            .create()
                        imeService.showDialog(dialog)
                    } else {
                        DiagLog.w(
                            "keymap", "open_url_no_confirm_surface",
                            mapOf("scheme" to action.url.substringBefore(":", "")),
                        )
                        openUrl(action.url)
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
            // 会话绑定：启动时捕获输入目标身份＋输入框纪元（树内
            // 已有机制：InputFieldKey 字段身份＋SherpaSpeechClient
            // 的 EditorEpoch，不另造代次），切回主线程分发前复检
            // 两者仍一致才分发——启动后换框／输入结束推进了纪元，
            // 或字段身份已变，过期结果直接丢弃并只记元数据，绝不
            // 把旧框脚本的动作写进新框。无输入框时身份为空、纪元
            // 仍比对（纪元推进仍能拦住旧会话结果）。
            is KeyboardAction.RunScriptAction -> {
                val captured = ScriptRunSession.capture(
                    service.currentInputEditorInfo,
                    SherpaSpeechClient.currentEditorEpoch(),
                )
                scriptJobs[action.scriptId]?.cancel()
                val job = appScope.launch(Dispatchers.Default) {
                    when (val outcome =
                        SkinScriptKeyBridge.runForKey(scriptRunner, action.scriptId)
                    ) {
                        is SkinScriptKeyBridge.KeyRunOutcome.Dispatch -> {
                            withContext(Dispatchers.Main) {
                                if (ScriptRunSession.isCurrent(
                                        captured,
                                        service.currentInputEditorInfo,
                                        SherpaSpeechClient.currentEditorEpoch(),
                                    )
                                ) {
                                    onKeyAction(outcome.action)
                                } else {
                                    DiagLog.w(
                                        "keymap", "script_key_stale_session",
                                        mapOf("scriptId" to action.scriptId),
                                    )
                                }
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
                scriptJobs[action.scriptId] = job
                job.invokeOnCompletion { scriptJobs.remove(action.scriptId, job) }
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
     * 起浏览器意图打开链接（open_url 确认后的唯一出口与无确认
     * 面时的回退出口共用）：失败只记异常类名，不记链接。
     */
    private fun openUrl(url: String) {
        try {
            service.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (ex: Exception) {
            DiagLog.w(
                "keymap", "open_url_failed",
                mapOf("error" to ex.javaClass.simpleName),
            )
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

/**
 * 按键脚本启动会话快照（纯数据，可单测）：输入目标身份
 * [InputFieldKey]（包名／字段 id／输入类型／imeOptions，IMS 与
 * 引擎联想共用的字段口径）＋输入框纪元（SherpaSpeechClient 的
 * EditorEpoch 现值，换框／输入结束时推进）。两者任一变化即判
 * 会话已变：同框收起再打开身份与纪元皆不变，复检放行；换框
 * 或输入结束必推进纪元，过期分发被拦。无输入框时身份为空、
 * 纪元照常比对（纪元推进仍能拦住旧框结果）。
 */
internal data class ScriptRunSession(
    val field: InputFieldKey?,
    val epoch: Long,
) {
    companion object {
        fun capture(editorInfo: EditorInfo?, epoch: Long): ScriptRunSession =
            ScriptRunSession(InputFieldKey.of(editorInfo), epoch)

        /** 字段口径复检（纯逻辑，可单测）。 */
        fun isCurrent(
            captured: ScriptRunSession,
            field: InputFieldKey?,
            epoch: Long,
        ): Boolean = captured.field == field && captured.epoch == epoch

        /** 编辑器口径复检：先取字段身份，再走字段口径。 */
        fun isCurrent(
            captured: ScriptRunSession,
            editorInfo: EditorInfo?,
            epoch: Long,
        ): Boolean = isCurrent(captured, InputFieldKey.of(editorInfo), epoch)
    }
}
