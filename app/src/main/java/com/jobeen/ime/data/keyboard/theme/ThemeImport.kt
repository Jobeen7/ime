package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import com.jobeen.ime.data.ThemeStore
import com.jobeen.ime.data.manager.KeyboardManager

/**
 * 旧主题导入的共享判定（主题设置页顶栏扫码与库页扫码共用，
 * 2026-10-28 库页扫码按前缀分流后抽出；判定与落盘逻辑与
 * 主题设置页既有 importTheme 相同，不得另写一套）：
 * 同 id 覆盖、槽位未满导入、槽位已满且新 id → 需选槽覆盖。
 * 本对象只做判定＋落盘＋生效偏好，不弹任何 UI；满槽时的
 * 选槽对话框由调用方（各页）自行呈现；不记输入/候选内容。
 */
object ThemeImport {
    sealed interface Outcome {
        data class Applied(val theme: KeyboardTheme) : Outcome
        data class NeedsOverwriteSlot(val theme: KeyboardTheme) : Outcome
    }

    /**
     * 导入一主题：同 id 原位替换、槽位未满追加时返回 Applied；
     * 槽位已满（MAX_CUSTOM_THEMES）且为新 id 时返回
     * NeedsOverwriteSlot（调用方弹选槽对话框，选定后调
     * [ThemeStore.overwrite]＋[apply]，与主题设置页既有流程一致）。
     */
    fun import(theme: KeyboardTheme): Outcome =
        if (ThemeStore.import(theme)) Outcome.Applied(theme) else Outcome.NeedsOverwriteSlot(theme)

    /** 与主题设置页 finishImport 相同的生效：关跟随、设主题 id。 */
    fun apply(context: Context, theme: KeyboardTheme) {
        KeyboardManager.Keyboard.setFollowSystem(context, false)
        KeyboardManager.Keyboard.setThemeId(context, theme.id)
    }
}
