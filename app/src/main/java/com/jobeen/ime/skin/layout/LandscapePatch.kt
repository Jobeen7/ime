// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.layout

/**
 * 横屏补丁展开（多主类型补充）：横屏补丁不是完整布局，而是
 * 对竖屏布局的增量——展开后得到完整横屏布局：
 *
 * - 行：补丁第 i 行与竖屏第 i 行按键级合并（同 stableId 的
 *   补丁键替换竖屏键；补丁新增 stableId 追加到行尾；竖屏键
 *   未被提及者保留原位）；补丁行数超出竖屏时多余行整行追加；
 *   竖屏行数超出补丁时多余行原样保留。
 * - 高度：补丁 heightPercent 优先，缺省回竖屏值。
 * - 身份：展开结果沿用竖屏布局的 layoutId 与 name（它就是
 *   该布局的横屏形态，不是新布局）。
 *
 * 本函数只做展开引擎。补丁文件形态（某布局文件声明自己是
 * 另一布局的横屏补丁）与快照工厂接线为后续项：文件侧未定
 * 义 patchOf 字段前，转换器应先展开再把完整横屏布局交给
 * 快照工厂（工厂只认完整布局，见 KeyboardTypeEntry 的
 * layoutIdLandscape 口径）。
 *
 * 纯函数，可直接单测。
 */
object LandscapePatch {

    fun expand(portrait: LayoutModel, patch: LayoutModel): LayoutModel {
        val rows = ArrayList<List<LayoutKeyModel>>(maxOf(portrait.rows.size, patch.rows.size))
        for (i in 0 until maxOf(portrait.rows.size, patch.rows.size)) {
            val baseRow = portrait.rows.getOrNull(i)
            val patchRow = patch.rows.getOrNull(i)
            rows += when {
                baseRow == null -> patchRow ?: emptyList()
                patchRow == null -> baseRow
                else -> mergeRow(baseRow, patchRow)
            }
        }
        return LayoutModel(
            layoutId = portrait.layoutId,
            name = portrait.name,
            rows = rows,
            heightPercent = patch.heightPercent ?: portrait.heightPercent,
        )
    }

    private fun mergeRow(
        baseRow: List<LayoutKeyModel>,
        patchRow: List<LayoutKeyModel>,
    ): List<LayoutKeyModel> {
        val patchById = patchRow.associateBy { it.stableId }
        val merged = ArrayList<LayoutKeyModel>(baseRow.size + patchRow.size)
        for (baseKey in baseRow) {
            merged += patchById[baseKey.stableId] ?: baseKey
        }
        val baseIds = baseRow.map { it.stableId }.toSet()
        for (patchKey in patchRow) {
            if (patchKey.stableId !in baseIds) merged += patchKey
        }
        return merged
    }
}
