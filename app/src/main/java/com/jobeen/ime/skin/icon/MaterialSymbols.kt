package com.jobeen.ime.skin.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.TextView
import com.jobeen.ime.skin.diag.DiagLog

/**
 * Material Symbols 图标源（参考项，2026-10-27）：
 * App 内置子集字体 `assets/fonts/material-symbols-subset.ttf`
 * （Google Material Symbols Rounded，Apache 2.0 许可，见下），
 * 仅含精选符号集；皮肤按名引用（keyOverrides.icon / 工具栏按钮 icon）。
 *
 * 许可说明（不要与 SF Symbols 混淆）：SF Symbols 是苹果平台专属、
 * 仅限苹果平台许可，Jime 绝不使用；Material Symbols 是 Google
 * 发布的开源图标字体（Apache License 2.0，可商用、可再分发）。
 * 注意：子集字体 name 表内仅含 Google 版权行；Apache 2.0 全文
 * 内嵌方式（2026-10-28 确认落地）：全文随包内嵌于
 * `assets/fonts/material-symbols-subset-LICENSE.txt`（与子集字体
 * 同目录，即发行物内的 LICENSE 副本，随 APK 一起分发）；
 * 子集化未改动字体版权信息（name 表 Google 版权行原样保留）。
 * 上游见 Google Fonts / material-design-icons 仓库。
 *
 * 子集化：上游完整可变字体 15,235,612 B → 实例化默认值
 * （FILL=0 描边、wght=400、opsz=24）后按码位子集化为
 * 12,676 B（59 个符号，见 [NAMES]），APK 增量实测见交付报告。
 * 上游来源（2026-10-28 核对）：Google 的 Material Symbols
 * Rounded 可变字体；google/fonts 仓库 ofl/materialsymbols 路径
 * 404 不存在，改从 google/material-design-icons 仓库
 * variablefont 目录取到同一文件（字节数与既往记录的
 * 15,235,612 B 一致，name 表与四轴一致）。
 * 注意：编辑器 23 快选中的 emoji_emotions 在上游不存在
 * （Rounded 可变字体、Outlined 可变字体、Google Fonts
 * v383 下发版三处均无此字形与码位，已非 Rounded 独有缺口），
 * 故本次只补 2 名（注册表 59 名）；emoji_emotions 在 App 侧
 * 仍走未知名回退（不以 .notdef 空白或他图标冒充支持），
 * 该名单独的编辑器侧处理不在本任务内。
 * 渲染时 Typeface 取字体默认实例（描边样式、常规粗细）。
 *
 * 回退口径：未知名 → null（调用方保留默认图标/文本，不拒皮）；
 * 字体加载失败 → null + 一次性 WARN 日志（只记异常类名）。
 * 本文件除 [glyphFor]/[isKnown] 外均需 Android 环境，单测只覆盖
 * 纯映射部分；字体文件有效性由交付时的 APK 内核对与真机验证。
 */
object MaterialSymbols {

    /** 子集字体在 assets 内的路径。 */
    const val ASSET_PATH = "fonts/material-symbols-subset.ttf"

    /** Apache 2.0 全文在 assets 内的路径（与子集字体同目录，随 APK 分发）。 */
    const val LICENSE_ASSET_PATH = "fonts/material-symbols-subset-LICENSE.txt"

    /** 精选符号集：Symbol 名 → 码位（与子集字体 cmap 一一对应）。 */
    val NAMES: Map<String, Int> = mapOf(
        "backspace" to 0xE14A,
        "space_bar" to 0xE256,
        "keyboard" to 0xE312,
        "keyboard_hide" to 0xE31A,
        "keyboard_return" to 0xE31B,
        "language" to 0xEA07,
        "mic" to 0xE31D,
        "mood" to 0xEA22,
        "sentiment_satisfied" to 0xE813,
        "content_paste" to 0xE14F,
        "content_copy" to 0xE14D,
        "content_cut" to 0xE14E,
        "select_all" to 0xE162,
        "undo" to 0xE166,
        "redo" to 0xE15A,
        "search" to 0xEF7A,
        "close" to 0xE5CD,
        "check" to 0xE668,
        "delete" to 0xE92E,
        "settings" to 0xE8B8,
        "tune" to 0xE429,
        "palette" to 0xE40A,
        "translate" to 0xE8E2,
        "visibility" to 0xE8F4,
        "visibility_off" to 0xE8F5,
        "volume_up" to 0xE050,
        "volume_off" to 0xE04F,
        "light_mode" to 0xE518,
        "dark_mode" to 0xE51C,
        "schedule" to 0xEFD6,
        "history" to 0xE8B3,
        "star" to 0xF09A,
        "favorite" to 0xE87E,
        "share" to 0xE80D,
        "download" to 0xF090,
        "upload" to 0xF09B,
        "edit" to 0xF097,
        "add" to 0xE145,
        "remove" to 0xE15B,
        "menu" to 0xE5D2,
        "more_horiz" to 0xE5D3,
        "chevron_left" to 0xE5CB,
        "chevron_right" to 0xE5CC,
        "expand_more" to 0xE5CF,
        "expand_less" to 0xE5CE,
        "arrow_back" to 0xE5C4,
        "arrow_forward" to 0xE5C8,
        "arrow_upward" to 0xE5D8,
        "arrow_downward" to 0xE5DB,
        "arrow_drop_down" to 0xE5C5,
        "abc" to 0xEB94,
        "numbers" to 0xEAC7,
        "grid_view" to 0xE9B0,
        "emoji_symbols" to 0xEA1E,
        "gif_box" to 0xE7A3,
        "keyboard_arrow_down" to 0xE313,
        "keyboard_arrow_up" to 0xE316,
        "subdirectory_arrow_left" to 0xE5D9,
        "home" to 0xE88A,    )

    /** 名称是否在子集注册表内（纯函数，可单测）。 */
    fun isKnown(name: String): Boolean = name in NAMES

    /** 名称 → 字形字符串；未知名返回 null（调用方回退默认）。 */
    fun glyphFor(name: String): String? {
        val codePoint = NAMES[name] ?: return null
        return String(Character.toChars(codePoint))
    }

    @Volatile
    private var cachedTypeface: Typeface? = null

    @Volatile
    private var typefaceFailedLogged = false

    /** 子集字体 Typeface（缓存；加载失败返回 null 并记一次性日志）。 */
    fun typeface(context: Context): Typeface? {
        cachedTypeface?.let { return it }
        val loaded = runCatching {
            Typeface.createFromAsset(context.assets, ASSET_PATH)
        }.getOrElse {
            if (!typefaceFailedLogged) {
                typefaceFailedLogged = true
                DiagLog.w(
                    "icon", "material_symbols_typeface_failed",
                    mapOf("error" to it.javaClass.simpleName),
                )
            }
            null
        }
        if (loaded != null) cachedTypeface = loaded
        return loaded
    }

    /**
     * 把 TextView 内容换成符号字形（按键图标渲染用）：
     * 未知名或字体加载失败返回 false（调用方保留原文本）。
     */
    fun applyToTextView(textView: TextView, context: Context, symbolName: String?): Boolean {
        if (symbolName == null) return false
        val glyph = glyphFor(symbolName) ?: return false
        val face = typeface(context) ?: return false
        textView.typeface = face
        textView.text = glyph
        return true
    }

    private val drawableCache = LinkedHashMap<String, Drawable>()

    /**
     * 工具栏图标 Drawable（白色字形位图，调用方按既有口径 setTint
     * 着色；未知名或字体失败返回 null，调用方绘制空白但动作仍可触发，
     * 与缺图标按钮的既有行为一致）。位图按 (名, 像素尺寸) 缓存，
     * 上限 128 项，超限清 oldest（工具栏图标数为个位数，恒不触发）。
     */
    fun drawable(context: Context, symbolName: String, sizePx: Int): Drawable? {
        val glyph = glyphFor(symbolName) ?: return null
        val face = typeface(context) ?: return null
        val size = sizePx.coerceAtLeast(1)
        val key = "$symbolName@$size"
        synchronized(drawableCache) {
            drawableCache[key]?.let { return it }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            // 字形按 opsz=24 设计：字号取 0.92× 框，基线居中。
            textSize = size * 0.92f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(glyph, size / 2f, y, paint)
        val result = BitmapDrawable(context.resources, bitmap)
        synchronized(drawableCache) {
            if (drawableCache.size >= 128) drawableCache.clear()
            drawableCache[key] = result
        }
        return result
    }
}
