// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * `clipboard?id=<条目>` deep link 的落点定位（WO52 4.2 遗留项）：
 * 在剪贴板历史列表（[ClipboardManager.getEntriesOrNull] 的返回顺序，
 * 即键盘面板同一顺序：置顶在前、组内按手动序号／时间倒序）里找
 * 指定 id 的下标；找不到返回 -1，调用方显示“条目不存在”、不崩溃。
 *
 * 纯函数：不碰数据库、不读 Context，可 JVM 单测。id <= 0 不是合法
 * 行 id（Room 自增自 1 起），直接返回 -1。
 */
object ClipboardEntryLocator {
    fun indexOf(entries: List<ClipboardManager.Entry>, id: Long): Int {
        if (id <= 0L) return -1
        return entries.indexOfFirst { it.id == id }
    }
}
