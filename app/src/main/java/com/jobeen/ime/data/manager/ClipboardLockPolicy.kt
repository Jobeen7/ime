// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 剪贴板面板锁定（WO52 5.4，纯决策）：
 *
 * - 锁定 → 操作后刷新列表、面板停留（搜索态一并保持，不收起）。
 * - 未锁定 → 走现有 `clipboard.return_after_paste` 口径（开才回键盘），
 *   不另写返回逻辑；回键盘时搜索态随面板关闭收起（既有状态机口径）。
 *
 * 与 Emoji／符号锁是三套独立机制（Emoji 板锁已定不做，不要混淆）。
 */
object ClipboardLockPolicy {

    /** 粘贴等操作后是否回键盘：仅未锁定且 return_after_paste 开时回。 */
    fun shouldReturnToKeyboard(locked: Boolean, returnAfterPaste: Boolean): Boolean =
        !locked && returnAfterPaste

    /** 搜索态是否保持：仅锁定时保持；未锁定且回键盘时随面板收起。 */
    fun shouldKeepSearch(locked: Boolean): Boolean = locked
}
