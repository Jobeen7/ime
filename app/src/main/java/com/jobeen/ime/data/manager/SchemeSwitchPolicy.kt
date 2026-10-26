// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 方案来源判定（旧切换编排的存活部分）：方案页列表用它标注每条
 * 方案来自内置（shared 层）还是用户（user 层）。其余切换状态机
 * （SchemeSwitchOrchestrator/Coordinator）已随多目录改写退场。
 */
object SchemeSwitchPolicy {

    /** 方案来源：内置（shared 层）或用户（user 层）。 */
    enum class SchemeSource {
        BUILTIN,
        USER,
    }

    /**
     * 来源判定：user 层有方案文件即算用户（用户覆盖优先）；否则一律算内置。
     * 两层都没有时回落内置。
     */
    fun classifySource(inUserDir: Boolean, inSharedDir: Boolean): SchemeSource =
        if (inUserDir) SchemeSource.USER else SchemeSource.BUILTIN
}
