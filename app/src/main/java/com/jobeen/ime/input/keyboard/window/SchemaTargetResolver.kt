// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

/**
 * 命名方案直切换的目标解析（重点候选 8-3，纯函数）：
 *
 * - value = 方案 ID（优先）或方案名原文；
 * - ID 精确命中优先于名（ID 是稳定标识，名可能重名/改名）；
 * - 空白值或无命中 → null（调用方丢弃并记日志，绝不切到
 *   错误方案、绝不回退为轮换）。
 */
object SchemaTargetResolver {

    data class SchemaRef(val id: String, val name: String)

    fun resolve(value: String, schemas: List<SchemaRef>): String? {
        if (value.isBlank()) return null
        schemas.firstOrNull { it.id == value }?.let { return it.id }
        return schemas.firstOrNull { it.name == value }?.id
    }
}
