package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * 键盘背景图调节（参考项，2026-10-27）：
 * appearance.background.imageAdjust 的强类型视图，全部字段可选。
 *
 * 字段语义（逐项独立回退，缺失/非法 = 该项不调节，永不拒皮）：
 * - brightnessPercent：亮度百分比，100 = 不变；<100 变暗、>100 变亮，
 *   逐通道线性乘以 percent/100（alpha 不动，结果钳制 0..255）。
 *   取值域 0..200，域外/非数值 = 该项丢弃。
 * - blurRadiusDp：模糊半径 dp，存在时**取代** background.blurRadius
 *   （不叠加，避免双重模糊语义不清）；缺失时沿用 blurRadius。
 *   取值域 0..25，与 blurRadius 同口径。
 * - rotationDegrees：顺时针旋转度数（Android 画布正方向：正值顺时针），
 *   有限值均接受，预处理期归一化到 [0, 360)；0 = 不旋转。
 *   旋转在裁剪之后、缩放到目标尺寸之前执行，画布扩展到容纳整图。
 * - cropLeft/cropTop/cropRight/cropBottom：裁剪量，源图尺寸的比例
 *   （0~1 坐标系，不是像素），各边独立；单边 >=1 或非数值 = 该边丢弃；
 *   同轴两边之和 >=1 = 该轴两边一并丢弃（否则裁出空图）。
 *   裁剪在解码之后、旋转之前执行。
 *
 * 全部调节只走冷路径（导入期 [BackgroundPreparer] 预计算），
 * 与既有“模糊只冷路径预计算、壁纸 capped 到键盘像素尺寸”同口径，
 * 渲染热路径不做任何逐帧计算。
 *
 * 纯函数：解析只读 JSON 树，不触碰 Android API，可直接单测。
 */
data class ImageAdjust(
    val brightnessPercent: Float? = null,
    val blurRadiusDp: Float? = null,
    val rotationDegrees: Float? = null,
    val cropLeft: Float? = null,
    val cropTop: Float? = null,
    val cropRight: Float? = null,
    val cropBottom: Float? = null,
) {
    /** 任一有效调节存在。 */
    fun hasEffect(): Boolean =
        brightnessPercent != null || blurRadiusDp != null || rotationDegrees != null ||
            cropLeft != null || cropTop != null || cropRight != null || cropBottom != null

    /**
     * 预处理产物键后缀（无调节 = 空串，保持旧产物键逐字节一致）。
     * 每项带独立标签（亮度 bri / 模糊 blr / 旋转 rot / 裁剪 cl·ct·cr·cb），
     * 避免不同边/不同项拼出同键；精度取舍：亮度·模糊·旋转 0.01 单位、
     * 裁剪 0.0001——低于该精度的差异复用同一产物（冷路径可接受）。
     */
    fun preparedKeySuffix(): String {
        if (!hasEffect()) return ""
        val sb = StringBuilder("_adj")
        brightnessPercent?.let { sb.append("_bri").append((it * 100).toInt()) }
        blurRadiusDp?.let { sb.append("_blr").append((it * 100).toInt()) }
        rotationDegrees?.let {
            val norm = ((it % 360f) + 360f) % 360f
            sb.append("_rot").append((norm * 100).toInt())
        }
        cropLeft?.let { sb.append("_cl").append((it * 10000).toInt()) }
        cropTop?.let { sb.append("_ct").append((it * 10000).toInt()) }
        cropRight?.let { sb.append("_cr").append((it * 10000).toInt()) }
        cropBottom?.let { sb.append("_cb").append((it * 10000).toInt()) }
        return sb.toString()
    }

    companion object {
        private val BRIGHTNESS_RANGE = 0f..200f
        private val BLUR_RANGE = 0f..25f

        /**
         * 解析 imageAdjust 节点：null（未写）→ null；非对象 → issue +
         * null；对象内逐字段校验，坏字段记 issue 并丢弃该字段；
         * 全部字段被丢弃 → null（等价于未调节，产物键不变）。
         * issue 的 path 前缀由调用方传入的 [path] 决定。
         */
        fun parse(node: JsonElement?, path: String, issues: MutableList<SkinIssue>): ImageAdjust? {
            if (node == null) return null
            val obj = node as? JsonObject
            if (obj == null) {
                issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "image_adjust_not_object")
                return null
            }
            fun ranged(name: String, range: ClosedFloatingPointRange<Float>): Float? {
                val raw = obj[name] ?: return null
                val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    ?.toFloat()
                if (value == null || !value.isFinite() || value !in range) {
                    issues += SkinIssue(
                        "$path.$name", SkinIssue.Kind.FIELD_FALLBACK, "out_of_range_or_invalid",
                    )
                    return null
                }
                return value
            }
            val rotationRaw = obj["rotationDegrees"]
            val rotation = if (rotationRaw == null) {
                null
            } else {
                val value = (rotationRaw as? JsonPrimitive)?.takeIf { !it.isString }
                    ?.doubleOrNull?.toFloat()
                if (value == null || !value.isFinite()) {
                    issues += SkinIssue(
                        "$path.rotationDegrees",
                        SkinIssue.Kind.FIELD_FALLBACK, "out_of_range_or_invalid",
                    )
                    null
                } else {
                    value
                }
            }
            var left = ranged("cropLeft", 0f..1f)
            var top = ranged("cropTop", 0f..1f)
            var right = ranged("cropRight", 0f..1f)
            var bottom = ranged("cropBottom", 0f..1f)
            // 单边 =1（裁掉整边）无另外一边可配对时同样丢弃该边。
            fun dropFullEdge(name: String, value: Float?): Float? {
                if (value != null && value >= 1f) {
                    issues += SkinIssue(
                        "$path.$name", SkinIssue.Kind.FIELD_FALLBACK, "crop_axis_overflow",
                    )
                    return null
                }
                return value
            }
            left = dropFullEdge("cropLeft", left)
            top = dropFullEdge("cropTop", top)
            right = dropFullEdge("cropRight", right)
            bottom = dropFullEdge("cropBottom", bottom)
            // 单边 =1 已被 ranged 的 0..1 接受？1f 在域内但裁掉整边：
            // 上下/左右和 >=1 时该轴两边一并丢弃（含单边 =1 的情形）。
            if (left != null && right != null && left + right >= 1f) {
                issues += SkinIssue(
                    "$path.cropLeft", SkinIssue.Kind.FIELD_FALLBACK, "crop_axis_overflow",
                )
                issues += SkinIssue(
                    "$path.cropRight", SkinIssue.Kind.FIELD_FALLBACK, "crop_axis_overflow",
                )
                left = null
                right = null
            }
            if (top != null && bottom != null && top + bottom >= 1f) {
                issues += SkinIssue(
                    "$path.cropTop", SkinIssue.Kind.FIELD_FALLBACK, "crop_axis_overflow",
                )
                issues += SkinIssue(
                    "$path.cropBottom", SkinIssue.Kind.FIELD_FALLBACK, "crop_axis_overflow",
                )
                top = null
                bottom = null
            }
            val adjust = ImageAdjust(
                brightnessPercent = ranged("brightnessPercent", BRIGHTNESS_RANGE),
                blurRadiusDp = ranged("blurRadiusDp", BLUR_RANGE),
                rotationDegrees = rotation,
                cropLeft = left,
                cropTop = top,
                cropRight = right,
                cropBottom = bottom,
            )
            return adjust.takeIf { it.hasEffect() }
        }
    }
}
