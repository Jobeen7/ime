package com.jobeen.ime.skin.runtime

import android.content.Context
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager

/**
 * 显示占位符预计算（定稿四.7 / 详设 §3.7）：记号集封闭，只在
 * 键盘构建期（布局初始化、方案切换后重建）求值一次，替换结果
 * 只存在于本次构建出的 KeyDef 文本中，永不写回皮肤文件/快照。
 */
object PlaceholderValues {

    const val SCHEMA_NAME = "schemaName"
    const val VERSION_NAME = "versionName"

    /** 当前占位符值表（两记号恒齐全：无当前方案时方案名为 ""）。 */
    fun current(context: Context): Map<String, String> = mapOf(
        SCHEMA_NAME to (KeyboardStateManager.getCurrentSchema()?.name ?: ""),
        VERSION_NAME to appVersionName(context),
    )

    private fun appVersionName(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
