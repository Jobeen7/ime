// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 剪贴板提示条状态（重点候选 8-5，纯持有者）：
 *
 * 键盘收起时监听仍在（见 ImeInputMethodService.onWindowHidden），
 * 新剪贴板文本到达即更新；提示条显示 [displayText]（验证码优先，
 * 否则最新剪贴板文本；皆空时提示条隐藏）。
 *
 * 隐私：本持有者只存内存，不写日志、不写磁盘；验证码只作提示用，
 * 抽取失败／误抽取均不回写剪贴板历史（历史写入与此无关）。
 */
class ClipboardPromptState {

    var latestText: String? = null
        private set

    var latestCode: String? = null
        private set

    val displayText: String? get() = latestCode ?: latestText

    /**
     * 本条文本的提示显示（接线轮 R4）：提示态跟踪的正是本条
     * 文本时取验证码优先的 displayText，否则用本条原文——
     * 避免提示态滞后时把旧验证码贴到新文本上。
     */
    fun displayTextFor(text: String): String =
        displayText.takeIf { latestText == text } ?: text

    fun onClipboardText(text: String) {
        latestText = text
        latestCode = VerificationCodeExtractor.extract(text)
    }

    fun clear() {
        latestText = null
        latestCode = null
    }
}
