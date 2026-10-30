// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

/**
* 符号成对决策（纯函数，可 JVM 单测；2026-10-28 截图 6 项）。
*
* 成对只作用于键盘按键提交（KeyActionListener 的 CommitAction），
* 不作用于候选栏选词与皮肤字面提交（LiteralCommitAction 必须
* 保持字面精确）。括号类无前文要求；引号类需前文为空（行首）/
* 空白/标点——字母数字（含中文）后不成对，避免把缩写、所有格
* 的引号误成对；前文读不到时保守不成对。
*/
object PairingPolicy {

/** 开符号 → 闭符号。 */
val PAIR_MAP: Map<Char, Char> = mapOf(
'(' to ')',
'[' to ']',
'{' to '}',
'"' to '"',
        '\u0027' to '\u0027',
'（' to '）',
        '\u3010' to '\u3011',
'《' to '》',
'「' to '」',
'『' to '』',
)

/** 引号类（开闭同形，需前文守卫）。 */
private val QUOTES = setOf('"', '\'')

fun isQuote(opener: Char): Boolean = opener in QUOTES

/** 开符号对应的成对文本（如 "(" → "()"）；非开符号返回 null。 */
fun pairText(opener: Char): String? {
val closer = PAIR_MAP[opener]?: return null
return "$opener$closer"
}

/**
* @param text 本次提交的文本（仅单字符开符号可成对）。
* @param beforeCursor 光标前 1 字（getTextBeforeCursor 结果）：
* null = 读取失败（引号类保守不成对）；"" = 行首（可成对）。
*/
fun shouldPair(
text: String,
pairingEnabled: Boolean,
isComposing: Boolean,
isPassword: Boolean,
beforeCursor: String?,
): Boolean {
if (!pairingEnabled || isComposing || isPassword) return false
if (text.length!= 1) return false
val opener = text[0]
if (opener!in PAIR_MAP) return false
if (opener in QUOTES) {
if (beforeCursor == null) return false
if (beforeCursor.isNotEmpty()) {
val prev = beforeCursor.last()
if (prev.isLetterOrDigit()) return false
}
}
return true
}
}
