// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.ResolvedLayout

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
 * 本函数只做展开引擎。补丁文件形态：布局文件顶层声明
 * `patchOf: <竖屏布局 id>`；快照工厂的横屏绑定路径认领该声明
 * （见 [expandToResolved]），竖屏绑定不认领；转换器预展开过
 * 的完整布局（无 patchOf）走既有路径，无须工厂展开。
 *
 * 纯函数，可直接单测。
 */
object LandscapePatch {

    /**
     * 补丁展开成快照可用的横屏已解析布局（纯函数）：
     * 展开结果的键身份沿用竖屏布局（键逻辑 id 由展开后模型的
     * layoutId 即竖屏 id 生成，皮肤键覆写按竖屏键命中）；
     * 已解析布局的 layoutId 仍取横屏绑定 id（patch 文件 id），
     * 供解析日志与绑定核对；name 与 heightPercent 取展开结果。
     */
    fun expandToResolved(
        patch: LayoutModel,
        portrait: LayoutModel,
        bindingId: String,
        generation: Int,
        placeholders: Map<String, String>,
    ): ResolvedLayout {
        val expanded = expand(portrait, patch)
        return ResolvedLayout(
            layoutId = bindingId,
            generation = generation,
            name = expanded.name,
            baseLayout = LayoutKeyDefBuilder.build(expanded, placeholders),
            baseLayoutLandscape = LayoutKeyDefBuilder.build(
                expanded, placeholders, landscape = true,
            ),
            heightPercent = expanded.heightPercent,
        )
    }

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
