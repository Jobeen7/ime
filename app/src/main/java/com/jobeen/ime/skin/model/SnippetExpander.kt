// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 模板文本展开（动作子集 snippet，对端 phrase 模板变量的
 * 最小实现）：
 *
 * - `{{date:格式}}`：公历日期，格式沿用对端写法直通 Java
 *   日期格式，唯两处转译：`eeee` → `EEEE`（全星期名，如
 *   星期一）、`eee` → `EEE`（短星期名，如 周一）；`MMM`
 *   等其余 token 与 Java 同义。地域取中文（与对端示例的
 *   “12月 31, 2020 / 星期一”同口径）。非法格式不抛错，
 *   该变量原样保留。
 * - `{{cursor}}`：光标位（一条模板至多一处有效，取首处）：
 *   展开结果去掉标记，[Expanded.cursorUnitsFromEnd] 给出
 *   标记后文本的 UTF-16 单元数——分发时上屏全文后按此数
 *   向左移光标（DPAD 步进，见分发分支注释的顺序残余）。
 * - 暂不支持（原样保留，不猜测）：`{{lunar_date:...}}`、
 *   `{{lunar_zodiac}}`（需农历历法）、自定义变量、
 *   `{{pasteboardContent}}`、`{{selectedText}}`（需宿主
 *   侧取值，短语系统范畴，不在本轮）。
 *
 * 纯函数（时间可注入），可直接单测。
 */
object SnippetExpander {

    data class Expanded(
        val text: String,
        /** 光标位后文本的 UTF-16 单元数；0 = 无光标位（光标自然在末尾）。 */
        val cursorUnitsFromEnd: Int,
    )

    private val DATE_VAR = Regex("""\{\{date:([^}]*)\}\}""")
    private const val CURSOR_VAR = "{{cursor}}"

    fun expand(template: String, now: LocalDateTime = LocalDateTime.now()): Expanded {
        val withDates = DATE_VAR.replace(template) { match ->
            val format = match.groupValues[1]
            runCatching {
                val javaFormat = format
                    .replace("eeee", "EEEE")
                    .replace("eee", "EEE")
                val date = Date.from(now.atZone(ZoneId.systemDefault()).toInstant())
                SimpleDateFormat(javaFormat, Locale.CHINA).format(date)
            }.getOrNull() ?: match.value
        }
        val cursorIndex = withDates.indexOf(CURSOR_VAR)
        if (cursorIndex < 0) return Expanded(withDates, 0)
        val before = withDates.substring(0, cursorIndex)
        val after = withDates.substring(cursorIndex + CURSOR_VAR.length)
        return Expanded(before + after, after.length)
    }
}
