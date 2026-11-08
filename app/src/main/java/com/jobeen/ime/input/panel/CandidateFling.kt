package com.jobeen.ime.input.panel

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 候选甩动减速（施工单 10 H2-10，CyIME 1.15.63 D24 原文：
 * 「候选栏和展开候选甩动后更快停下；去掉横滑的边缘回弹」，
 * 源码 ui/keyboard/CandidateFling.kt）。
 *
 * CyIME 的做法：样条减速的摩擦乘 4 倍（CANDIDATE_FLING_FRICTION = 4f），
 * 同样一甩，停下用的时间和滑过的距离都只剩约 36%（4^-0.736，
 * 系统样条减速的距离、时长都跟摩擦的 -0.736 次方成正比）。
 * Jime 是 View 体系，没有 Compose 的 splineBasedDecay；等价点是
 * OverScroller.setFriction()——同一套 AOSP 样条减速，摩擦同样
 * 以乘积形式出现。展开候选网格（CandidateGridView）的甩动走
 * OverScroller，创建时按本策略设摩擦。
 *
 * 范围说明（按代码现状核实，非臆测）：
 * - 横条（KawaiiPanelView）根本没有甩动惯性：拖动即 scrollX 硬钳制
 *   在 [-maxScrollX, 0]，抬手即停；「更快停下」在横条上无改动对象。
 * - 横向边缘回弹：横条硬钳制无回弹；展开网格甩动本身 overX/overY
 *   均为 0、不过界；Jime 候选区不存在横滑边缘回弹，「去回弹」
 *   一侧无改动对象。展开网格拖拽的竖向橡皮筋（stretch＋260ms
 *   回弹）是拖拽手感，不是横滑回弹，按保原有行为不动。
 * - GridKeyboardView／SidePanelView／ClipboardView／PreeditPinnerView
 *   的 OverScroller 不是候选区，不动。
 *
 * 手感是固定倍率（与 CyIME 同款常量），未做成设置项滑杆：
 * CyIME 原文也没有用户可调开关。
 */
object CandidateFlingPolicy {
    /** CyIME 同款倍数。 */
    const val FRICTION_MULTIPLIER = 4f

    /** Android ViewConfiguration.getScrollFriction() 的默认值。 */
    const val DEFAULT_SCROLL_FRICTION = 0.015f

    /** 展开候选网格甩动用的摩擦：默认 ×4。 */
    fun candidateFriction(): Float = DEFAULT_SCROLL_FRICTION * FRICTION_MULTIPLIER

    // 以下两函数是 AOSP SplineOverScroller 样条减速公式的主机可测镜像
    // （INFLEXION 0.35、DECELERATION_RATE ln(0.78)/ln(0.9)、physicalCoeff
    // = density × 160 × 0.84），只用于证明「摩擦 ×4 → 距离／时长约剩
    // 36%」；真机行为由 OverScroller 本身执行，不走这里。参数须为正
    // （velocity 可为负表示反向，0 返回 0）。
    private val decelerationRate: Double = ln(0.78) / ln(0.9)

    private fun splineDeceleration(velocityPxPerSec: Float, friction: Float, density: Float): Double {
        val physicalCoeff = density.toDouble() * 160.0 * 0.84
        return ln(0.35 * abs(velocityPxPerSec.toDouble()) / (friction.toDouble() * physicalCoeff))
    }

    /** 样条甩动滑过的距离（像素，带方向符号）。 */
    fun splineFlingDistancePx(velocityPxPerSec: Float, friction: Float, density: Float): Float {
        if (velocityPxPerSec == 0f) return 0f
        val l = splineDeceleration(velocityPxPerSec, friction, density)
        val decelMinusOne = decelerationRate - 1.0
        val physicalCoeff = density.toDouble() * 160.0 * 0.84
        val magnitude = friction.toDouble() * physicalCoeff *
            exp(decelerationRate / decelMinusOne * l)
        return (if (velocityPxPerSec > 0f) magnitude else -magnitude).toFloat()
    }

    /** 样条甩动停下用的时长（毫秒，恒为正）。 */
    fun splineFlingDurationMs(velocityPxPerSec: Float, friction: Float, density: Float): Float {
        if (velocityPxPerSec == 0f) return 0f
        val l = splineDeceleration(velocityPxPerSec, friction, density)
        val decelMinusOne = decelerationRate - 1.0
        return (1000.0 * exp(l / decelMinusOne)).toFloat()
    }
}
