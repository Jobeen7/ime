// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction

/**
 * 工具栏通知动作映射（接线轮 R1）：
 *
 * 通知以按钮 id 作目标命中工具栏键时，只有动作可落到工具栏
 * 钮上（渲染器无背景／前景样式槽，上下滑无手势槽——该两项
 * 本轮不做，见交回边界说明）。映射只取与面板动作精确对应
 * 的三项，其余命令无面板等价动作、按钮保持原动作（调用方
 * 不记日志，避免每次重建刷屏；命中本身已在锁定层可见）。
 */
object ToolbarNotificationAction {

    fun panelActionFor(action: SkinAction?): PanelAction? {
        val command = action as? SkinAction.Command ?: return null
        return when (command.action) {
            WhitelistAction.UNDO -> PanelAction.Undo
            WhitelistAction.REDO -> PanelAction.Redo
            WhitelistAction.HIDE_KEYBOARD -> PanelAction.CloseKeyboard
            else -> null
        }
    }
}
