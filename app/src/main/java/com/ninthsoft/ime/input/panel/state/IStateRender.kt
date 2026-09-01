package com.ninthsoft.ime.input.panel.state

import com.ninthsoft.ime.input.panel.IRenderer

/**
 * 每个面板状态对应的渲染器。
 *
 * 状态自身不再关心“如何绘制”，而是由对应的 StateRender 在状态进入时决定：
 *  - [createToolbarRenderer]：如何渲染底部菜单栏（工具栏）。
 *  - [showExpand] / [hideExpand]：如何渲染（展示/收起）展开的功能栏（候选、菜单、编辑、剪贴板等）。
 *
 * 这样 [com.ninthsoft.ime.input.panel.KawaiiPanel] 仅需委托给 StateRender，
 * 而不再耦合各状态的具体渲染细节。
 */
interface IStateRender {

    /** 渲染菜单栏：创建并返回该状态下用于绘制底部栏的 [IRenderer]。 */
    fun createToolbarRenderer(): IRenderer

    /** 渲染展开的功能栏：展示该状态对应的扩展区域。[isExpanded] 表示面板是否处于展开态。 */
    fun showExpand(isExpanded: Boolean)

    /** 收起展开的功能栏。 */
    fun hideExpand()
}
