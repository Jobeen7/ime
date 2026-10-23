package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction

/**
 * 覆写装配器（详设 §4.2）：键盘构建期把快照中的逐键覆写一次性
 * 合并进出厂布局，产出普通 KeyDef 列表——按键热路径零新增查表、
 * 零解析。手势引擎只消费 Behavior/Popup 数据，本类不触碰任何
 * 阈值与分发逻辑（定稿一.3 数据 Provider 口径）。
 *
 * 纪律：
 * - 无覆写命中且无全局音效时恒等返回输入引用；
 * - 命中才复制 KeyDef，出厂静态 Layout 对象绝不原地修改；
 * - Repeat / DoubleTap / SwipeMoveCursor / Popup.Menu 等不可
 *   覆写槽一律原样带入副本（详设 §3.3-4）。
 */
object KeyOverrideApplier {

    /** 逐键音效解析结果（三级链：逐键 → 全局 tap → 出厂，详设 §7.2）。 */
    sealed interface SoundPlan {
        /** 沿用出厂行为（标准点按音）。 */
        data object Inherit : SoundPlan

        /** 播放已接纳的皮肤音效资产。 */
        data class Play(val assetId: String) : SoundPlan

        /** 显式静音（覆写 sound:""）。 */
        data object Muted : SoundPlan
    }

    /**
     * @param layoutName 布局 NAME（仅用于诊断日志）。
     * @param placeholders 占位符预计算值（[PlaceholderValues.current]）。
     */
    fun apply(
        layoutName: String,
        factoryLayout: List<List<KeyDef>>,
        snapshot: SkinSnapshot?,
        placeholders: Map<String, String>,
    ): List<List<KeyDef>> {
        if (snapshot == null) return factoryLayout
        val overrides = snapshot.keyOverrides
        val hasGlobalSound = snapshot.soundBindings.defaultTapAssetId != null
        if (overrides.isEmpty() && !hasGlobalSound) return factoryLayout

        var applied = 0
        val result = factoryLayout.map { row ->
            row.map { def ->
                val override = def.id?.let { overrides[it] }?.takeIf { it.hasEffect() }
                val sound = resolveSound(override, snapshot.soundBindings)
                if (override == null && sound == SoundPlan.Inherit) {
                    def
                } else {
                    applied++
                    copyWith(def, override, placeholders, sound)
                }
            }
        }
        if (applied == 0) return factoryLayout
        DiagLog.d(
            "keymap", "override_applied_to_keyboard",
            mapOf("layout" to layoutName, "appliedCount" to applied),
        )
        return result
    }

    /**
     * 覆写是否含有效内容：全为 sequence 占位的覆写不产生任何
     * 运行时差异（占位整槽回退出厂，详设 §3.6），按未命中处理。
     */
    private fun KeyOverride.hasEffect(): Boolean {
        if (label != null || soundRef != null) return true
        if (tap != null && tap != SkinAction.SequencePlaceholder) return true
        if (swipeUp != null && swipeUp != SkinAction.SequencePlaceholder) return true
        if (swipeDown != null && swipeDown != SkinAction.SequencePlaceholder) return true
        if (longPress != null && longPress.any { it != SkinAction.SequencePlaceholder }) return true
        return false
    }

    /** 音效三级解析（详设 §7.2-1）：逐键未接纳时按字段级回退到全局级。 */
    fun resolveSound(override: KeyOverride?, bindings: SoundBindings): SoundPlan {
        val ref = override?.soundRef
        if (ref != null) {
            if (ref.isEmpty()) return SoundPlan.Muted
            if (ref in bindings.admitted) return SoundPlan.Play(ref)
            // 引用未接纳（缺失/被剔）→ 回退全局级，不静音不报错
        }
        val tap = bindings.defaultTapAssetId
        return if (tap != null) SoundPlan.Play(tap) else SoundPlan.Inherit
    }

    private fun copyWith(
        def: KeyDef,
        override: KeyOverride?,
        placeholders: Map<String, String>,
        sound: SoundPlan,
    ): KeyDef {
        val appearance = if (override?.label != null) {
            relabel(def.appearance, substitute(override.label, placeholders))
        } else {
            def.appearance
        }

        var behaviors = def.behaviors
        var extraPopup: KeyDef.Popup.Keyboard? = null
        if (override != null) {
            override.tap?.let { tap ->
                if (tap != SkinAction.SequencePlaceholder) {
                    behaviors = behaviors.replaceSlot<KeyDef.Behavior.Press> {
                        KeyDef.Behavior.Press(toRuntime(tap))
                    }
                }
            }
            val upWritten = override.swipeUp?.takeIf { it != SkinAction.SequencePlaceholder }
            val downWritten = override.swipeDown?.takeIf { it != SkinAction.SequencePlaceholder }
            if (upWritten != null || downWritten != null) {
                val factorySwipe = behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
                val merged = KeyDef.Behavior.Swipe(
                    upAction = upWritten?.let { toRuntime(it) } ?: factorySwipe?.upAction,
                    downAction = downWritten?.let { toRuntime(it) } ?: factorySwipe?.downAction,
                )
                behaviors = behaviors.replaceSlot<KeyDef.Behavior.Swipe> { merged }
            }
            override.longPress?.let { longPress ->
                val effective = longPress.filter { it != SkinAction.SequencePlaceholder }
                if (effective.size == 1) {
                    behaviors = behaviors.replaceSlot<KeyDef.Behavior.LongPress> {
                        KeyDef.Behavior.LongPress(toRuntime(effective.single()))
                    }
                } else if (effective.size >= 2) {
                    // 多项长按（定稿四.7）：只装 Popup.Keyboard 候选条——
                    // 视图的长按监听是单槽，若同时装 LongPress 行为，
                    // 后注册的行为监听会遮蔽候选条监听、条目不可达；
                    // 单项才退化为 LongPress 直接执行（详设 §4.2-2）。
                    behaviors = behaviors.filterNot { it is KeyDef.Behavior.LongPress }.toSet()
                    extraPopup = KeyDef.Popup.Keyboard(
                        label = override.label?.let { substitute(it, placeholders) }
                            ?: displayTextOf(def.appearance),
                        keys = effective.map { toRuntime(it) },
                    )
                }
                // effective 为空（全占位）→ 整槽回退出厂，不动 behaviors
            }
        }

        val factoryPopups = def.popups
        val popups: Array<KeyDef.Popup>? = when {
            extraPopup == null -> factoryPopups
            factoryPopups == null -> arrayOf(extraPopup)
            else -> Array(factoryPopups.size + 1) { index ->
                // 出厂弹窗在前，候选条监听最后注册胜出
                if (index < factoryPopups.size) factoryPopups[index] else extraPopup
            }
        }

        return KeyDef(
            appearance = appearance,
            behaviors = behaviors,
            popups = popups,
            id = def.id,
        ).also { copy ->
            when (sound) {
                is SoundPlan.Play -> copy.skinSoundAssetId = sound.assetId
                SoundPlan.Muted -> copy.skinSoundMuted = true
                SoundPlan.Inherit -> Unit
            }
        }
    }

    /** 替换某一类槽位行为：同类旧行为移除、新行为加入；其余原样。 */
    private inline fun <reified T : KeyDef.Behavior> Set<KeyDef.Behavior>.replaceSlot(
        replacement: () -> T,
    ): Set<KeyDef.Behavior> =
        filterNot { it is T }.toSet() + replacement()

    /**
     * label 覆写只换主显文本；副显/图标/几何等其余外观字段逐项
     * 原样带入新外观（Appearance 非 data class，手工重建；
     * AltText/ImageText 是 Text 子类，必须先于 Text 匹配）。
     */
    private fun relabel(appearance: KeyDef.Appearance, label: String): KeyDef.Appearance =
        when (appearance) {
            is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
                displayText = label,
                altText = appearance.altText,
                altTextTranslationY = appearance.altTextTranslationY,
                mainTextTranslationY = appearance.mainTextTranslationY,
                altTextSize = appearance.altTextSize,
                textSize = appearance.textSize,
                textStyle = appearance.textStyle,
                percentWidth = appearance.percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
                visibility = appearance.visibility,
            )
            is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
                displayText = label,
                textSize = appearance.textSize,
                textStyle = appearance.textStyle,
                src = appearance.src,
                percentWidth = appearance.percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
            )
            is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
                displayText = label,
                textSize = appearance.textSize,
                textStyle = appearance.textStyle,
                percentWidth = appearance.percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
                visibility = appearance.visibility,
            )
            // 图片/侧栏外观无文本面：label 记录在案但无处呈现，保持原外观
            is KeyDef.Appearance.Image, is KeyDef.Appearance.SidePannel -> appearance
        }

    private fun displayTextOf(appearance: KeyDef.Appearance): String = when (appearance) {
        is KeyDef.Appearance.Text -> appearance.displayText
        is KeyDef.Appearance.AltText -> appearance.displayText
        is KeyDef.Appearance.ImageText -> appearance.displayText
        is KeyDef.Appearance.Image, is KeyDef.Appearance.SidePannel -> ""
    }

    private val TOKEN = Regex("\\$[A-Za-z][A-Za-z0-9]*")

    /**
     * 占位符替换（详设 §3.7）：只作用于显示文本，未知记号原样
     * 保留 + 一条 DEBUG 日志（只记记号名，绝不记模板原文）。
     */
    fun substitute(template: String, placeholders: Map<String, String>): String =
        TOKEN.replace(template) { match ->
            val name = match.value.substring(1)
            placeholders[name] ?: run {
                DiagLog.d("keymap", "placeholder_unresolved", mapOf("token" to name))
                match.value
            }
        }

    /** 强类型动作 → 运行时动作（详设 §3.2 映射表；占位类型不得到达此处）。 */
    fun toRuntime(action: SkinAction): KeyboardAction = when (action) {
        is SkinAction.Text -> KeyboardAction.KeySequenceAction(action.value)
        is SkinAction.Command -> when (action.action) {
            WhitelistAction.COMMIT_DIRECT ->
                KeyboardAction.LiteralCommitAction(requireNotNull(action.value))
            WhitelistAction.LITERAL_NEWLINE -> KeyboardAction.CommitAction("\n")
            WhitelistAction.ENTER -> KeyboardAction.ReturnAction()
            WhitelistAction.DELETE -> KeyboardAction.BackspaceAction
            WhitelistAction.DELETE_FORWARD -> KeyboardAction.DeleteForwardAction
            WhitelistAction.SPACE -> KeyboardAction.SpaceAction
            WhitelistAction.CURSOR_LEFT -> KeyboardAction.CursorMoveAction(-1)
            WhitelistAction.CURSOR_RIGHT -> KeyboardAction.CursorMoveAction(1)
            WhitelistAction.ROTATE_SCHEMA -> KeyboardAction.RotateSchema
            WhitelistAction.OPEN_SCHEMA_PICKER -> KeyboardAction.ShowInputMethodPickerAction
            WhitelistAction.SWITCH_SYSTEM_IME -> KeyboardAction.LangSwitchAction
            WhitelistAction.OPEN_SYSTEM_IME_PICKER -> KeyboardAction.OpenSystemImePickerAction
            WhitelistAction.HIDE_KEYBOARD -> KeyboardAction.HideKeyboardAction
            WhitelistAction.CAPS -> KeyboardAction.CapsAction
            WhitelistAction.SWITCH_LAYOUT ->
                KeyboardAction.LayoutSwitchAction(requireNotNull(action.value))
            WhitelistAction.RESUME -> KeyboardAction.ResumeAction
            WhitelistAction.CLEAR -> KeyboardAction.ClearAction
            WhitelistAction.UNDO -> KeyboardAction.UndoAction
            WhitelistAction.REDO -> KeyboardAction.RedoAction
            WhitelistAction.VOICE_INPUT -> KeyboardAction.VoiceInputAction()
        }
        SkinAction.SequencePlaceholder ->
            error("sequence 占位不得转运行时动作（详设 §3.6）")
    }
}
