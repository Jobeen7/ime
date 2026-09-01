package com.ninthsoft.ime.base.util

import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

object FontManager {
    private val fonts = ConcurrentHashMap<String, Typeface>()

    fun fromAsset(context: Context, path: String): Typeface = synchronized(fonts) {
        fonts[path] ?: Typeface.createFromAsset(
            context.applicationContext.assets,
            path,
        ).also {
            fonts[path] = it
        }
    }
}
