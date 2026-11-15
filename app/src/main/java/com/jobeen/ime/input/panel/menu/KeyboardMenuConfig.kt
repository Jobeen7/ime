// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.menu

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 键盘菜单管理（WO52 12-⑨）：
 *
 * - 菜单项＝注册表 [KeyboardMenuEntries] 的 id 顺序列；用户在设置页
 *   增删排序，存偏好（`keyboard_menu` 文件，键 `entries`），菜单
 *   打开时按存档顺序分 8 项一页渲染（与旧两页 8＋8 同口径）；
 * - C-L-40：删光就是空菜单，不再回落默认全量（恢复默认走设置页
 *   “恢复默认”按钮）；未配置过（null）仍回落默认——首次安装菜单
 *   不空白；未知 id／重复逐项跳过，不拖垮整列；
 * - 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
object KeyboardMenuConfigOps {

    /** 每页项数（4 列 × 2 行，与 MenuGridView 现有分页一致）。 */
    const val PAGE_SIZE = 8

    const val PREFS_NAME = "keyboard_menu"

    /** 存储键。 */
    const val KEY_ENTRIES = "entries"

    /** 默认顺序：注册表全量 id（两页 8＋8）。 */
    fun defaultIds(): List<String> = KeyboardMenuEntries.ALL.map { it.id }

    /**
     * 抉择：未知 id 与重复（取首次出现）逐项跳过；空表返空（C-L-40：
     * 删光就是空菜单）；null（未配置过／损坏）回落默认全量——首次
     * 安装菜单不空白。
     */
    fun resolve(ids: List<String>?): List<String> {
        if (ids == null) return defaultIds()
        val known = defaultIds().toSet()
        val out = ArrayList<String>(ids.size)
        for (id in ids) {
            if (id !in known) continue
            if (id in out) continue
            out += id
        }
        return out
    }

    /** 分页：按 [PAGE_SIZE] 切页（顺序即菜单顺序）。 */
    fun pages(ids: List<String>): List<List<String>> =
        resolve(ids).chunked(PAGE_SIZE)

    /** 添加：未知／已在列时原样返回（调用方不写盘）。 */
    fun add(ids: List<String>, id: String): List<String> {
        if (id !in defaultIds().toSet()) return ids
        if (id in ids) return ids
        return ids + id
    }

    /** 删除指定 id：不在列时原样返回。 */
    fun remove(ids: List<String>, id: String): List<String> {
        if (id !in ids) return ids
        return ids.filter { it != id }
    }

    /** 移动指定下标到目标下标：任一下标越界或同位时原样返回。 */
    fun move(ids: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        if (fromIndex !in ids.indices || toIndex !in ids.indices) return ids
        if (fromIndex == toIndex) return ids
        val next = ids.toMutableList()
        val item = next.removeAt(fromIndex)
        next.add(toIndex, item)
        return next
    }

    fun serialize(ids: List<String>): String =
        JsonArray(ids.map { JsonPrimitive(it) }).toString()

    /** 解析：无记录／损坏／非数组时回 null（调用方按默认抉择）。 */
    fun parse(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonArray
                ?: return null
            arr.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** 键盘菜单配置存储：加载即抉择后顺序（删光可为空表）。 */
interface KeyboardMenuStore {
    fun load(): List<String>
    fun save(ids: List<String>)
}

/** 内存实现（单测用）。 */
class InMemoryKeyboardMenuStore(
    initial: List<String>? = null,
) : KeyboardMenuStore {
    private var stored: List<String>? = initial
    override fun load(): List<String> = KeyboardMenuConfigOps.resolve(stored)
    override fun save(ids: List<String>) {
        stored = ids
    }
}

/** 偏好实现：一键存 id 数组 JSON 串。 */
class SharedPrefsKeyboardMenuStore(context: Context) : KeyboardMenuStore {

    private val prefs = context.getSharedPreferences(
        KeyboardMenuConfigOps.PREFS_NAME, Context.MODE_PRIVATE,
    )

    override fun load(): List<String> =
        KeyboardMenuConfigOps.resolve(
            KeyboardMenuConfigOps.parse(prefs.getString(KeyboardMenuConfigOps.KEY_ENTRIES, null)),
        )

    override fun save(ids: List<String>) {
        prefs.edit()
            .putString(
                KeyboardMenuConfigOps.KEY_ENTRIES,
                KeyboardMenuConfigOps.serialize(ids),
            ).apply()
    }
}
