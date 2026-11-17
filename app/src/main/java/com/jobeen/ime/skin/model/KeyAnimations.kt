package com.jobeen.ime.skin.model

/**
 * 按键动效定义（审计 H 项）：对端四类动效的 Jime 模型。
 *
 * - scale：按压缩放（对端唯一在参考皮肤中实际出现的类型）；
 * - cartoon：帧序列（图片资产 id 列表 + 帧率 + 目标缩放 + 层级）；
 * - physics：粒子（图片/随机图 + 起止位置 + 随机偏移 + 透明/
 *   旋转开关 + 角度）；位置单位见 [KeyAnimationPositionUnit]；
 * - transform：变换（目标层/触发/时长/延迟/缓动/重复/自动回弹/
 *   完成恢复/按住保持/抬起时长/锚点/位置单位/脱离 + Jime 终态）。
 *
 * 终态说明：审计列出的 transform 字段未含终态属性（动画到
 * 哪里）；Jime 终态字段（endScale / endTranslation / endAlpha /
 * endRotationDeg）为本模型补齐的必填语义——无终态的 transform
 * 只记时序、不产生可见变化，渲染层按终态插值。
 * 回退口径：scale/cartoon/physics 缺关键字段（缩放值/图片）
 * 时整条丢弃；transform 其余字段非法时逐项回退默认值。
 * 纯解析，可直接单测；渲染见 KeyView（仅新字段触发时生效）。
 */
sealed interface KeyAnimationDef {

    /** 按压缩放：压下缩、抬起回弹（autoReverse=false 时抬起保持缩放）。 */
    data class Scale(
        val scale: Float,
        val pressDurationMs: Int,
        val releaseDurationMs: Int,
        val autoReverse: Boolean,
    ) : KeyAnimationDef

    /** 帧序列：按压时循环播放图片帧，抬起停止并隐藏。 */
    data class Cartoon(
        /** 图片资产逻辑 id（装配期过滤为已接纳资产）。 */
        val images: List<String>,
        val fps: Int,
        val targetScale: Float?,
        val zPosition: Float?,
    ) : KeyAnimationDef

    /** 粒子：按压时从起点向终点抛图片（随机图时每粒随机取图）。 */
    data class Physics(
        val images: List<String>,
        val randomImage: Boolean,
        val startX: Float?,
        val startY: Float?,
        val endX: Float?,
        val endY: Float?,
        val randomOffsetX: Float?,
        val randomOffsetY: Float?,
        val useOpacity: Boolean,
        val useRotation: Boolean,
        val startAngleDeg: Float?,
        val endAngleDeg: Float?,
        val durationMs: Int,
        val positionUnit: KeyAnimationPositionUnit,
    ) : KeyAnimationDef

    /** 变换：触发时对键面做终态插值动画。 */
    data class Transform(
        /**
         * 目标（工具项 14 渲染口径，见 resolveTransformTarget）：
         * null/空白 = 整键；face = 常态图片面；pressedFace = 按压
         * 图片面；label = 主标签；未知串回落整键并记一次 WARN。
         * 前景层无名称身份，不在本词汇内。
         */
        val target: String?,
        val trigger: KeyAnimationTrigger,
        val durationMs: Int,
        val delayMs: Int,
        /** 三次贝塞尔控制点 [x1, y1, x2, y2]；null = 用 easing。 */
        val bezier: List<Float>?,
        val easing: KeyAnimationEasing,
        val repeatCount: Int,
        val autoReverse: Boolean,
        val restoreOnFinish: Boolean,
        val holdUntilRelease: Boolean,
        val releaseDurationMs: Int,
        val anchorX: Float?,
        val anchorY: Float?,
        val positionUnit: KeyAnimationPositionUnit,
        val detached: Boolean,
        /** 终态（null = 该属性不变）。 */
        val endScale: Float?,
        val endTranslationX: Float?,
        val endTranslationY: Float?,
        val endAlpha: Float?,
        val endRotationDeg: Float?,
    ) : KeyAnimationDef
}

enum class KeyAnimationTrigger {
    PRESS,
    RELEASE,
}

enum class KeyAnimationPositionUnit {
    DP,
    FRACTION,
}
