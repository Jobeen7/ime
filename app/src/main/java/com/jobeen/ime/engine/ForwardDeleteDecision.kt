// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

/**
 * 组字中前删判定（重点候选 8-9，纯函数）：
 *
 * - 码右有字符（caret 未到 preedit 末尾）→ 送 Delete 给 Rime，
 *   只删编码右侧（Rime 消费该键，不碰宿主文本）；
 * - 码右无字符（caret 已到末尾／preedit 为空／caret 越界）
 *   → 不动。
 *
 * 越界一律按无码右处理（fail-safe）：composition 快照是异步
 * 回写、可能滞后于最新组字；宁可漏删一次（用户再按一次），
 * 也绝不把 Delete 漏给宿主删宿主文本。调用方另以
 * isVirtual=false 送内部合成键作第二道保险（未消费时透传
 * 门控直接拦下，不补发宿主 DEL）。
 */
object ForwardDeleteDecision {

    fun shouldDeleteCodeRight(preedit: String?, cursorPos: Int): Boolean {
        if (preedit.isNullOrEmpty()) return false
        if (cursorPos < 0 || cursorPos >= preedit.length) return false
        return true
    }
}
