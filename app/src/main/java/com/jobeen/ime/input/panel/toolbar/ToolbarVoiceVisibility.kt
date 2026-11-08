// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import com.jobeen.ime.base.speech.VoiceInputSwitchStore
import com.jobeen.ime.input.panel.PanelAction
import java.io.File

/**
 * 工具栏／菜单语音入口可见性（施工单 10 H2-13，CyIME D22/F7）：
 * 语音总开关关闭时，语音入口（[PanelAction.ToggleVoice]）隐藏；
 * 其余动作一律可见。纯函数，供 JVM 测试与渲染器／菜单共用。
 *
 * 不在此口判定的入口（有意）：
 * - 自定义工具栏按钮：点按另有绑定，语音只是长按；关闭后长按仍由
 *   启动入口门控（KeyboardWindowView.startVoiceInputLocked 提前返回），
 *   不隐藏整个钮（否则会连点按动作一并拿掉）。
 * - 空格长按：空格键不是麦克风入口，不隐藏；同理由启动入口门控。
 * - 皮肤布局内的语音键：键宽参与行和（行和恒为 1.0），藏键会破布局；
 *   同理由启动入口门控，键在、点按无动作。
 */
fun isToolbarActionVisible(action: PanelAction, voiceEnabled: Boolean): Boolean =
    action != PanelAction.ToggleVoice || voiceEnabled

/**
 * 构造点注入用：读语音总开关当前值（与 KeyboardWindowView 两处
 * 启动门控同口：同一文件、同一 shared 单实例；设置页与输入法服务
 * 同主进程，切换后即时生效）。
 */
fun voiceInputEnabled(context: Context): Boolean =
    VoiceInputSwitchStore.shared(
        File(context.filesDir, VoiceInputSwitchStore.FILE_NAME),
    ).enabled
