package com.jobeen.ime.skin.model

import kotlin.math.roundToInt

/**
 * 样式三态、数值归一化、逐键宽度接纳、图标槽位表（第 5 批 §6，纯 JVM 部分）：
 *
 * - 三态样式 + 空值继承（对方 K8/K9/K10 的 Jime 形）：
 *   常态／按下／锁定三态，空值 = 继承（锁定 → 按下 → 常态）；
 *   按下色为空时按亮度自动推导（亮色加深 12%、暗色提亮 15%，
 *   alpha 下限 48）。Jime 既有 KeyStyleRole 只有常态／按下角色，
 *   锁定态此前只在 capsLocked 前景色单点存在——本对象给三态
 *   解析提供可测的纯规则，渲染接线仍走既有 KeyStyleResolver／
 *   StyleStatePriority（capsLocked 口径不变，不另起一套）。
 * - 覆盖层独立存储：既有 SkinOverlayStore 已实现（token 为
 *   null = 删除覆盖恢复默认、空串 = 显式解绑；工具栏 buttons
 *   null = 未配置、空列表 = 已配置空列表），本批不重复实现，
 *   只以测试钉住语义（见 Batch5SkinGuardsTest）。
 * - 内置模板只刷新内置 id：Jime 内置布局覆盖分属各皮肤代次，
 *   不进全局布局索引（JimeImporter 内置分流），用户自建布局
 *   不受内置刷新影响——本批不另加种子逻辑，只记审计结论。
 * - 原坐标皮肤与行布局分开构建：Jime 布局模型即行布局
 *   （percentWeights 行和 = 1.0），无原坐标皮肤模型；“改过即
 *   脱离原皮肤坐标转行布局”在 Jime 无对应物——编辑器侧属
 *   父代理所有，本批只落行布局接纳与测试（见下）。
 * - 图标槽位化（对方 S7 的 Jime 形）：只收既有槽，不重命名：
 *   keys 组 = 键 icon／pressedIcon；toolbar 组 = 钮 icon／
 *   expandIcon／collapseIcon；system 组在 Jime 无皮肤可寻址槽
 *   （系统图标是代码内 Material 常量，不属皮肤槽），故为空表
 *   ——空表是审计结论，不是缺槽待补（为 system 编造槽名会
 *   让皮肤写出渲染层不认的字段）。
 * - 逐键宽度覆盖（对方 CyIME widths 的 Jime 形）：只存差量
 *   （factor），同行按比例缩放，落地换算为 Jime 行和 = 1.0 的
 *   percentWidths 语义并校验行和；编辑器侧属父代理所有，本批
 *   只做运行侧接纳与测试（与 KeyOverrideApplier 的
 *   renormalizedWidths 同口径：跨行键宽度恒为出厂值，非跨行
 *   键按原行非跨行宽和归一化）。
 * - 字体配置哈希跳过与数值归一化：字体键见 ImageDecodePolicy
 *   的 FontCacheKey（路径 + mtime + 长度）；数值读入即归一化
 *   ／钳制见 [clampedFloat]/[clampedInt]（非法／越界 → 默认值，
 *   与 SkinValidator.validateRangedFloat 同口径：字符串不算数值
 *   的口径在校验层，纯数值层只处理数值本身）。
 */
object SkinStyleStates {

    /** 样式三态。 */
    enum class State { NORMAL, PRESSED, LOCKED }

    /**
     * 三态解析：空值继承（锁定 → 按下 → 常态）；按下态无显式值
     * 时按常态色自动推导（不回落常态原值，与对方 K10 同款）。
     * @return ARGB Int，或 null（常态亦空时）。
     */
    fun resolveThreeState(
        normal: Int?,
        pressed: Int?,
        locked: Int?,
        state: State,
    ): Int? = when (state) {
        State.NORMAL -> normal
        State.PRESSED -> pressed ?: normal?.let { derivePressedColor(it) }
        State.LOCKED -> locked ?: pressed ?: normal
    }

    /**
     * 按下色自动推导：亮色（相对亮度 ≥0.5）加深 12%，暗色提亮
     * 15%，alpha 下限 48（对方 K10：亮加深 12%、暗提亮 15%、
     * alpha 下限 48）。
     */
    fun derivePressedColor(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        fun adjust(channel: Int): Int = if (luminance >= 0.5) {
            (channel * 0.88).roundToInt().coerceIn(0, 255)
        } else {
            (channel + (255 - channel) * 0.15).roundToInt().coerceIn(0, 255)
        }
        val outAlpha = maxOf(a, 48)
        return (outAlpha shl 24) or (adjust(r) shl 16) or (adjust(g) shl 8) or adjust(b)
    }

    /** 数值读入即归一化／钳制：null／NaN／无限／越界 → 默认值。 */
    fun clampedFloat(value: Float?, range: ClosedFloatingPointRange<Float>, default: Float): Float {
        if (value == null || !value.isFinite() || value !in range) return default
        return value
    }

    fun clampedInt(value: Int?, range: IntRange, default: Int): Int {
        if (value == null || value !in range) return default
        return value
    }
}

/** 逐键宽度接纳（运行侧，行和 = 1.0 语义）。 */
object KeyWidthAcceptance {

    /** 行内一键：id（null = 匿名键，不接受覆写）、出厂 percentWidth、跨行数。 */
    data class RowKey(val id: String?, val percentWidth: Float, val rowSpan: Int = 1)

    sealed interface Result {
        data class Accepted(val rows: List<List<Float>>) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * 接纳逐键宽度差量（factor，按键 id → 缩放因子）：
     * 非跨行键先按因子缩放，再按原行非跨行宽和归一化（与
     * KeyOverrideApplier.renormalizedWidths 同口径）；跨行键
     * 宽度恒为出厂值（因子对其无效，不报错、不缩放）。
     * 校验：因子必须有限且 >0；每行新宽和（含跨行键）与 1.0
     * 之差 ≤ 1e-3，否则整行拒绝（不部分接纳）。
     */
    fun acceptFactors(
        rows: List<List<RowKey>>,
        factors: Map<String, Float>,
    ): Result {
        val outRows = mutableListOf<List<Float>>()
        for (row in rows) {
            val original = row.map { it.percentWidth }
            var hasOverride = false
            val scaled = row.map { key ->
                val factor = key.id?.let { factors[it] }
                if (key.rowSpan > 1 || factor == null || factor == 1f) {
                    key.percentWidth
                } else {
                    if (!factor.isFinite() || factor <= 0f) {
                        return Result.Rejected("width_factor_invalid:${key.id}")
                    }
                    hasOverride = true
                    key.percentWidth * factor
                }
            }
            if (!hasOverride) {
                outRows += original
                continue
            }
            val nonSpan = row.indices.filter { row[it].rowSpan <= 1 }
            val sumOrig = nonSpan.sumOf { original[it].toDouble() }
            val sumScaled = nonSpan.sumOf { scaled[it].toDouble() }
            if (sumOrig <= 0.0 || sumScaled <= 0.0) {
                return Result.Rejected("width_row_sum_invalid")
            }
            val ratio = (sumOrig / sumScaled).toFloat()
            val renewed = row.mapIndexed { index, key ->
                if (key.rowSpan > 1) original[index] else scaled[index] * ratio
            }
            val total = renewed.sumOf { it.toDouble() }
            if (kotlin.math.abs(total - 1.0) > 1e-3) {
                return Result.Rejected("width_row_sum_not_one:$total")
            }
            if (renewed.any { it <= 0f }) return Result.Rejected("width_non_positive")
            outRows += renewed
        }
        return Result.Accepted(outRows)
    }
}

/** 图标槽位表（只收既有槽，不重命名已有槽）。 */
object IconSlotCatalog {
    /** keys 组：键级图标槽（SkinKeyModel 的 icon／pressedIcon 字段名）。 */
    val KEYS: List<String> = listOf("key.icon", "key.pressedIcon")

    /** toolbar 组：工具栏图标槽（SkinToolbar 的按钮 icon／expandIcon／collapseIcon）。 */
    val TOOLBAR: List<String> = listOf(
        "toolbar.buttons[].icon", "toolbar.expandIcon", "toolbar.collapseIcon",
    )

    /**
     * system 组：Jime 无皮肤可寻址的系统槽（系统图标是代码内
     * Material 常量，经 IconSlot 解析，不属皮肤字段），故为空表。
     */
    val SYSTEM: List<String> = emptyList()

    fun grouped(): Map<String, List<String>> = mapOf(
        "keys" to KEYS,
        "toolbar" to TOOLBAR,
        "system" to SYSTEM,
    )

    fun isKnown(slot: String): Boolean =
        slot in KEYS || slot in TOOLBAR || slot in SYSTEM
}
