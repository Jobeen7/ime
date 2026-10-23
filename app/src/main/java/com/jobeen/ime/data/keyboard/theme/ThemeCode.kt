package com.jobeen.ime.data.keyboard.theme

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.jobeen.ime.data.theme.CompactTheme
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.util.EnumMap
import kotlinx.serialization.json.Json

/**
 * 主题短码（IMEKBTHEME: 前缀 + CompactTheme JSON），由主题设置页
 * 既有实现原样抽出为共享对象（定稿八.7：沿用现行二维码格式，
 * 不扩展、不改动）；皮肤库的「配色短码」导出复用同一入口。
 */
object ThemeCode {

    const val PREFIX = "IMEKBTHEME:"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(theme: KeyboardTheme): String =
        PREFIX + json.encodeToString(CompactTheme.from(theme))

    fun decode(raw: String): KeyboardTheme? {
        val content = raw.trim()
        if (!content.startsWith(PREFIX)) return null
        val body = content.removePrefix(PREFIX)
        runCatching {
            return json.decodeFromString<CompactTheme>(body).toKeyboardTheme()
        }
        // 兼容旧版「IMEKBTHEME:主题id」格式
        return KeyboardTheme.byId(body).takeIf { it.id == body }
    }

    fun toBitmap(text: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            this[EncodeHintType.CHARACTER_SET] = "UTF-8"
            this[EncodeHintType.MARGIN] = 1
        }
        return BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 512, 512, hints)
    }
}
