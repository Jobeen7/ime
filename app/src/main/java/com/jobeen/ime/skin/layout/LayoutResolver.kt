package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.ResolvedLayout
import kotlinx.serialization.json.JsonElement

/**
 * 布局引用解析（纯函数，B3 详设 §3.4）：layout.json 原始树 →
 * 校验 → 构建基础 KeyDef 结构。文件读取与索引查询由快照工厂
 * 完成，本类只做「树 → 已解析布局」的确定性变换，可直接单测。
 *
 * 解析只在快照构建冷路径发生（导入/应用/启动加载/布局新代次
 * 提交四种触发，待核 7 定死：全部经 SkinSnapshotFactory 构建
 * 快照，无其他入口）。
 */
object LayoutResolver {

    sealed interface Outcome {
        /** 校验通过（可含逐字段回退/丢槽问题清单）并已构建基础结构。 */
        data class Resolved(
            val resolved: ResolvedLayout,
            val issues: List<LayoutIssue>,
        ) : Outcome

        /** 整布局无效：问题清单逐项带字段路径。 */
        data class Invalid(val issues: List<LayoutIssue>) : Outcome
    }

    /**
     * @param tree layout.json 原始树；null（文件缺失/解析失败）按整布局无效。
     * @param generation 入库代次（随结果携带，供日志与代次核对）。
     * @param placeholders 占位符预计算值（标签构建期替换，与出厂路径同时机）。
     */
    fun resolve(
        tree: JsonElement?,
        layoutId: String,
        generation: Int,
        placeholders: Map<String, String>,
    ): Outcome =
        when (val validation = LayoutValidator.validate(tree, layoutId)) {
            is LayoutValidation.Invalid -> Outcome.Invalid(validation.issues)
            is LayoutValidation.Valid -> Outcome.Resolved(
                resolved = ResolvedLayout(
                    layoutId = layoutId,
                    generation = generation,
                    name = validation.layout.name,
                    baseLayout = LayoutKeyDefBuilder.build(validation.layout, placeholders),
                    baseLayoutLandscape = LayoutKeyDefBuilder.build(
                        validation.layout, placeholders, landscape = true,
                    ),
                    heightPercent = validation.layout.heightPercent,
                ),
                issues = validation.issues,
            )
        }
}
