// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 脚本（WO52 8.1）：字段与建表 SQL 以本文件 @Entity 声明为准
 * （表名／列名／列序／索引见 MIGRATION_16_17，SQL 与 Room 生成的
 * schema `app/schemas/.../AppDatabase/17.json` 一致）。
 *
 * - `iconType`：图标类型（静态证据只确认是 Int，未确认枚举值，
 *   按原值透传；种子脚本用 0）。
 * - `resultInsertState`：运行后是否插入结果（按 Boolean 读，
 *   0/1）；`closeWindowState`：运行后是否关闭窗口（同理）。
 *   两态在 Jime 管理页暂不设开关（语义只确认到布尔，未确认 UI
 *   文案与默认值口径）：新建默认 false，编辑时原值透传。
 * - `downloadURL`：可选下载地址（可空）。
 * - `date`：毫秒时间戳（创建／更新时间）。
 */
@Entity(
    tableName = "ScriptItem",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["date"]),
        Index(value = ["name"]),
    ],
)
data class ScriptItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uuid: String,
    val iconName: String,
    val iconType: Int,
    val name: String,
    val content: String,
    val isFavorite: Boolean,
    val sort: Int,
    val date: Long,
    val allowPasteboard: Boolean,
    val resultInsertState: Boolean,
    val closeWindowState: Boolean,
    val downloadURL: String?,
)

/** 脚本标签（WO52 8.1）。 */
@Entity(
    tableName = "ScriptTag",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["date"]),
    ],
)
data class ScriptTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uuid: String,
    val name: String,
    val color: String,
    val date: Long,
)

/** 脚本↔标签交叉引用（WO52 8.1；双向级联删除）。 */
@Entity(
    tableName = "ScriptTagCrossRef",
    primaryKeys = ["scriptId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ScriptItem::class,
            parentColumns = ["id"],
            childColumns = ["scriptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScriptTag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class ScriptTagCrossRef(
    val scriptId: Long,
    val tagId: Long,
)

/** 脚本变量（WO52 8.1；key/value/isSecure 与常用语变量同形）。 */
@Entity(
    tableName = "ScriptVariable",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["date"]),
    ],
)
data class ScriptVariable(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uuid: String,
    val key: String,
    val value: String,
    val isSecure: Boolean,
    val date: Long,
)
