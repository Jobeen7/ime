package com.ninthsoft.ime.data

import android.content.Context
import com.ninthsoft.ime.R

/** 根据键盘布局类型返回标签文案（九宫格 / 15键 / 全键盘）。 */
fun schemaLayoutTag(context: Context, layout: String): String = when (layout) {
    "T9" -> context.getString(R.string.tag_layout_t9)
    "T15" -> context.getString(R.string.tag_layout_t15)
    else -> context.getString(R.string.tag_layout_full)
}