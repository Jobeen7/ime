// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

/**
 * 区域门控（对端架构 §2 规则的 Jime 映射）：
 *
 * - 缺失样式 = 区域不创建（不是透明占位）：没有定义区域样式
 *   时，该区域根本不存在，不画透明底、不占透明区。
 * - 皮肤可压制区域：皮肤显式压制某区域时，该区域缺席（不是
 *   透明），与缺失同效。
 *
 * Jime 落地口径（与对端原文的差异，刻意保留）：
 * - 工具栏区与候选栏是同一条区视图（候选栏覆盖工具栏区，
 *   见架构 §2 与 Jime 单视图双渲染模型）：条区视图本身永不
 *   GONE（GONE 会连候选一起藏），门控只作用于工具栏按钮区
 *   （中间按钮区）——按钮区缺席时条区仍可画候选。
 * - 工具栏按钮区的 Jime 存在条件是工具栏内容（toolbar 节点）
 *   与用户隐藏偏好；toolbarStyle 只是按钮区样式，不是按钮区
 *   存在条件——故运行期不把“缺 toolbarStyle”直接等同于按钮
 *   区缺席（否则全部现存皮肤的工具栏按钮都会消失，属回归）。
 *   本函数的缺失分支供转换器使用：转换对端皮肤时，缺
 *   toolbarStyle 的对端皮肤不产生工具栏按钮区（转换产物不含
 *   工具栏节点），而不是产出一个透明按钮区。
 * - 按键区：Jime 模型中尚无 keyboardStyle 节点（按键区背景
 *   走方案配色、内容走布局表），本函数仍以同一规则覆盖按键
 *   区（stylePresent 由未来 keyboardStyle 节点或布局解析供给）；
 *   皮肤压制字段（suppressKeyboard）已解析进快照，视图侧
 *   缺席接线为后续项（按键区缺席 = 空白键盘，需单独决策，
 *   不在本项静默接线）。
 *
 * 纯函数，可直接单测。
 */
object RegionGate {

    /** 区域是否创建：样式存在且未被皮肤压制时创建；否则缺席。 */
    fun isCreated(stylePresent: Boolean, suppressed: Boolean): Boolean =
        stylePresent && !suppressed

    /** 工具栏按钮区是否创建（转换器口径：样式存在且未压制）。 */
    fun isToolbarZoneCreated(toolbarStylePresent: Boolean, suppressed: Boolean): Boolean =
        isCreated(toolbarStylePresent, suppressed)

    /** 按键区是否创建（同规则；stylePresent 供给方见上）。 */
    fun isKeyboardZoneCreated(keyboardStylePresent: Boolean, suppressed: Boolean): Boolean =
        isCreated(keyboardStylePresent, suppressed)

    /** 工具栏按钮区是否被皮肤压制（运行期接线读此值）。 */
    fun isToolbarSuppressed(suppressToolbar: Boolean): Boolean = suppressToolbar
}
