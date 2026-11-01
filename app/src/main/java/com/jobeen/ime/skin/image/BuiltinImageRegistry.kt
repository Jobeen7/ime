package com.jobeen.ime.skin.image

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * App 内置图注册表（工具项 9）：assetImage 名 → drawable。
 *
 * 解析口径为 Android 资源名精确匹配：皮肤写 assetImage 名即
 * Jime 的 drawable 资源名（如 ic_keyboard_language）；无此资源、
 * 空白名、查找或取图异常时一律返回 null，调用方按旧口径回退
 * （ToolbarButtonBackgroundResolver 记 WARN、无背景），不猜映射。
 * 对端专属名（如 chineseState）在 Jime 无同名资源时保持未解析，
 * 不映射为语义相近但不等价的图标。
 */
object BuiltinImageRegistry {

    /**
     * 可注入的提供方：资源名 → drawable。
     * [lookupId] 为资源名 → 资源 id（0 = 无此资源），生产用
     * Resources.getIdentifier；[drawableForId] 为 id → drawable。
     */
    fun provider(
        lookupId: (String) -> Int,
        drawableForId: (Int) -> Drawable?,
    ): (String) -> Drawable? = { name ->
        if (name.isBlank()) {
            null
        } else {
            val id = runCatching { lookupId(name) }.getOrNull() ?: 0
            if (id == 0) {
                null
            } else {
                runCatching { drawableForId(id) }.getOrNull()
            }
        }
    }

    /** 生产绑定：Resources.getIdentifier + Context.getDrawable。 */
    fun contextProvider(context: Context): (String) -> Drawable? = provider(
        lookupId = { name ->
            context.resources.getIdentifier(name, "drawable", context.packageName)
        },
        drawableForId = { id -> context.getDrawable(id) },
    )
}
