package com.jobeen.ime.skin.script

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier

/**
 * 按键触发脚本的桥接（脚本接线波次）。
 *
 * 皮肤键只能引用应用级脚本 id（run_script 的 value），绝不能
 * 携带脚本源码：皮肤内嵌脚本已废止，脚本内容只存在于应用级
 * 脚本库；键按下时，执行的是用户自己在管理页维护的脚本。
 *
 * 返回值语义（Wave-1 留待本波次裁定）：返回值是数据，不是
 * 通用指令——只有恰为一个无值白名单动作格式名时，才转成分
 * 发动作；其余一律不解释、不执行：
 * - 带值动作（run_script、switch_layout、open_url 等）不成立：
 *   返回值没有值通道，无法补值；且 run_script 本身不成立，
 *   故返回值不可能递归触发另一脚本；
 * - voice_input 不成立：长按独占键的录音副作用只能来自显式
 *   按键，不能由脚本返回值绕过槽位规则触发；
 * - 未知名、空值、非字符串（沙箱已归一为 null）一律不成立。
 *
 * 线程：[runForKey] 同步执行、走调用线程；Runner 预算最高
 * 500ms，故调用方必须在主线程之外调用（分发层走后台调度器，
 * 回主线程再分发转成的动作）。日志纪律：本桥接不记返回值
 * 原文与脚本日志行；调用方只记脚本 id 与 [NoActionReason]。
 */
object SkinScriptKeyBridge {

    /** 按键脚本执行结果：要么一个待分发动作，要么无动作及原因。 */
    sealed interface KeyRunOutcome {
        data class Dispatch(val action: KeyboardAction) : KeyRunOutcome
        data class NoAction(val reason: NoActionReason) : KeyRunOutcome
    }

    enum class NoActionReason {
        NOT_FOUND,
        DISABLED,
        NO_HANDLER,
        FAILED,
        NO_RETURN_ACTION,
    }

    /**
     * 同步执行一次按键脚本。调用方必须在主线程之外调用。
     * @param event 固定为 "press"：按键触发的脚本事件名。
     */
    fun runForKey(
        runner: SkinScriptRunner,
        scriptId: String,
        event: String = "press",
    ): KeyRunOutcome {
        return when (val result = runner.runScript(scriptId, event)) {
            is SkinScriptResult.Ok -> {
                val action = result.value
                    ?.let { resolveReturnAction(it) }
                    ?.let { KeyOverrideApplier.toRuntime(it) }
                if (action != null) {
                    KeyRunOutcome.Dispatch(action)
                } else {
                    KeyRunOutcome.NoAction(NoActionReason.NO_RETURN_ACTION)
                }
            }
            is SkinScriptResult.Skipped -> KeyRunOutcome.NoAction(
                when (result.reason) {
                    SkinScriptSkipReason.NOT_FOUND -> NoActionReason.NOT_FOUND
                    SkinScriptSkipReason.DISABLED -> NoActionReason.DISABLED
                    SkinScriptSkipReason.NO_HANDLER -> NoActionReason.NO_HANDLER
                },
            )
            is SkinScriptResult.Failed -> KeyRunOutcome.NoAction(NoActionReason.FAILED)
        }
    }

    /**
     * 脚本返回值 → 可分发动作（纯函数）：仅当返回值恰为一个
     * 无值白名单动作的格式名时成立（voice_input 除外，见类文档）。
     */
    fun resolveReturnAction(value: String): SkinAction? {
        val action = WhitelistAction.fromFormatName(value) ?: return null
        if (action.valueRule != WhitelistAction.ValueRule.FORBIDDEN) return null
        if (action == WhitelistAction.VOICE_INPUT) return null
        return SkinAction.Command(action)
    }
}
