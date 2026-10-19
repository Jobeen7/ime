package com.jobeen.ime.data.database

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromIntList(value: List<Int>): String = value.joinToString(",")

    @TypeConverter
    fun toIntList(value: String): List<Int> =
        // 损坏数据（异常备份带入非数字段）不应让整表读取抛
        // NumberFormatException：无法解析的段直接丢弃，其余照常还原
        if (value.isEmpty()) emptyList() else value.split(",").mapNotNull { it.toIntOrNull() }
}
