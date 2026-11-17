// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.layout.CompactLayoutGenerator
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.backspaceKey
import com.jobeen.ime.input.keyboard.key.compactAlphabetKey
import com.jobeen.ime.input.keyboard.key.layoutSwitchKey
import com.jobeen.ime.input.keyboard.key.newlineKey
import com.jobeen.ime.input.keyboard.key.peroidKey
import com.jobeen.ime.input.keyboard.key.returnKey
import com.jobeen.ime.skin.runtime.EnterKeyVariant
import com.jobeen.ime.input.keyboard.key.schemaSwitchKey
import com.jobeen.ime.input.keyboard.key.sidePannelKey
import com.jobeen.ime.input.keyboard.key.spaceKey

/**
 * 14 键紧凑拼音键盘（松烟 merged14 思路，自研）：
 * QWERTY 行内相邻键合并的 14 个字母键（qw/er/ty/ui/op/as/df/gh/
 * jk/l/zx/cv/bn/m），点按发首字母、长按发次字母，同一词库与学习词，
 * 组合消歧见 PinYinUtil.possibleCompactCombinations 与 native
 * CompactPinyinMap。侧栏／标点模式／回车变体与 T9 同口径。
 *
 * 行和（每行行内宽＋覆盖 span 宽＝1.0，spanScale＝1）：
 * - 行 0：侧栏 .15＋4×.1675＋退格 .18＝1.0
 * - 行 1：侧栏 .15＋5×.134＋换行 .18＝1.0
 * - 行 2：侧栏 .15＋5×.134＋回车 .18（跨行）＝1.0
 * - 行 3：.15＋.13＋.41＋.13＝.82＋回车跨行 .18＝1.0
 */
@SuppressLint("ViewConstructor")
class T14Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    layout: List<List<KeyDef>> = effectiveLayout(
        context,
        EnterKeyVariant.resolve(context),
    ),
) : CompactKeyboard(
    context,
    colors,
    layout,
) {

    companion object {
        const val NAME = "T14"

        /**
         * 生效布局（与 T9Keyboard.effectiveLayout 同口径）：出厂布局
         * 经当前皮肤快照的覆写装配；无快照/无命中时恒等返回出厂布局。
         */
        fun effectiveLayout(context: Context, returnVariant: Variant): List<List<KeyDef>> {
            val raw = buildLayout(returnVariant)
            return raw
        }

        /** 出厂布局（纯函数）：分组按 QWERTY 顺序流排，行宽和见类注释。 */
        fun buildLayout(returnVariant: Variant): List<List<KeyDef>> {
            val groups = CompactLayoutGenerator.groupingFor(14)?.groups
                ?: error("unreachable T14 grouping")
            fun key(index: Int, width: Float) = compactAlphabetKey(
                groups[index].letters, width,
                id = when (index) {
                    0 -> KeyIds.T14.T14_KEY_1
                    1 -> KeyIds.T14.T14_KEY_2
                    2 -> KeyIds.T14.T14_KEY_3
                    3 -> KeyIds.T14.T14_KEY_4
                    4 -> KeyIds.T14.T14_KEY_5
                    5 -> KeyIds.T14.T14_KEY_6
                    6 -> KeyIds.T14.T14_KEY_7
                    7 -> KeyIds.T14.T14_KEY_8
                    8 -> KeyIds.T14.T14_KEY_9
                    9 -> KeyIds.T14.T14_KEY_10
                    10 -> KeyIds.T14.T14_KEY_11
                    11 -> KeyIds.T14.T14_KEY_12
                    12 -> KeyIds.T14.T14_KEY_13
                    else -> KeyIds.T14.T14_KEY_14
                },
            )
            return listOf(
                listOf(
                    sidePannelKey(rowSpan = 3, visableRow = 4),
                    key(0, 0.1675f), key(1, 0.1675f), key(2, 0.1675f), key(3, 0.1675f),
                    backspaceKey(0.18f, id = KeyIds.T14.T14_DELETE),
                ),
                listOf(
                    key(4, 0.134f), key(5, 0.134f), key(6, 0.134f), key(7, 0.134f), key(8, 0.134f),
                    newlineKey(0.18f, id = KeyIds.T14.T14_NEWLINE),
                ),
                listOf(
                    key(9, 0.134f), key(10, 0.134f), key(11, 0.134f), key(12, 0.134f), key(13, 0.134f),
                    returnKey(percentWidth = 0.18f, rowSpan = 2, variant = returnVariant, id = KeyIds.T14.T14_ENTER),
                ),
                listOf(
                    layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f, id = KeyIds.T14.T14_LAYOUT_NUMBER),
                    peroidKey(percentWidth = 0.13f, id = KeyIds.T14.T14_PERIOD),
                    spaceKey(percentWidth = 0.41f, id = KeyIds.T14.T14_SPACE),
                    schemaSwitchKey(0.13f, id = KeyIds.T14.T14_SCHEMA),
                ),
            )
        }
    }

    override fun name(): String {
        return NAME
    }
}
