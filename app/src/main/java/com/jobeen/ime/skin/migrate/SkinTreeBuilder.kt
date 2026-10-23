package com.jobeen.ime.skin.migrate

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.StyleField
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 由 ColorScheme 构建 v3 皮肤树（旧主题另存 §3.3 与预设转 v3 共用）：
 *
 * - styles 写全 28 个颜色叶子 + surfaceStyle，值以 #AARRGGBB 写实
 *   （toast 在 ColorScheme 中已是回退后的实值，定稿十章注口径）。
 * - [geometry] 非空时写 appearance 四几何（另存口径甲：写当前
 *   全局偏好现值，presence 全指定）；为空时不写 appearance
 *   （预设口径：几何由偏好决定，保 1.1.14 行为不变，详设 §3.3）。
 */
object SkinTreeBuilder {

    fun buildTree(
        skinId: String,
        name: String,
        scheme: KeyboardColors.ColorScheme,
        geometry: GeometryFallbacks?,
    ): JsonObject = buildJsonObject {
        put("formatVersion", 3)
        putJsonObject("meta") {
            put("id", skinId)
            put("name", name)
            put("version", "1.0")
        }
        if (geometry != null) {
            putJsonObject("appearance") {
                put("cornerRadius", geometry.cornerRadius.toDouble())
                put("keyHMargin", geometry.keyHMargin.toDouble())
                put("keyVMargin", geometry.keyVMargin.toDouble())
                put("bottomInsetDp", geometry.bottomInsetDp.toDouble())
            }
        }
        putJsonObject("styles") {
            for (field in StyleField.entries) {
                val path = field.path
                if ('.' !in path) {
                    put(path, SkinJson.formatColor(field.valueIn(scheme)))
                }
            }
            putJsonObject("panel") {
                for (field in StyleField.entries) {
                    if (field.path.startsWith("panel.")) {
                        put(field.path.removePrefix("panel."), SkinJson.formatColor(field.valueIn(scheme)))
                    }
                }
            }
            putJsonObject("pinner") {
                for (field in StyleField.entries) {
                    if (field.path.startsWith("pinner.")) {
                        put(field.path.removePrefix("pinner."), SkinJson.formatColor(field.valueIn(scheme)))
                    }
                }
            }
            put("surfaceStyle", scheme.surfaceStyle.name)
        }
    }
}
