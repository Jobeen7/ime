// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

/**
 * 删除选中文本的纯判定（#deleteText）：
 *
 * - 组字中恒否：组字期选区即组字区，连接侧删除会绕开引擎
 *   损坏组字态（删组字走引擎退格通道，两者不得混用）；
 * - 选区为空（null 或空串）恒否：无选区 = 空操作，绝不
 *   回退为删字符（删字符是退格的工作）；
 * - 其余恒是：调用方以 commitText("", 1) 删选区。
 *
 * 纯函数，可直接单测；输入连接读取与提交在分发层。
 */
object DeleteTextPolicy {

    fun shouldDelete(isComposing: Boolean, selectedText: CharSequence?): Boolean =
        !isComposing && !selectedText.isNullOrEmpty()
}
