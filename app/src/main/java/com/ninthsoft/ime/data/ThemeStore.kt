package com.ninthsoft.ime.data

import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import com.ninthsoft.ime.data.keyboard.theme.KeyboardThemePresets
import com.ninthsoft.ime.data.theme.CompactTheme
import com.ninthsoft.ime.data.theme.ReadableTheme
import kotlinx.serialization.json.Json
import java.io.File

object ThemeStore {
    private const val THEMES_FILE = "themes.json"
    const val MAX_CUSTOM_THEMES = 2

    // themes.json 使用可读的关键字（长 key）与 #AARRGGBB 十六进制颜色，美化排版
    private val readableJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // 兼容旧版：长 key + Int 颜色（KeyboardTheme）或紧凑短 key（CompactTheme）
    private val fallbackJson = Json { ignoreUnknownKeys = true }

    fun refresh() {
        val file = File(App.themesDir, THEMES_FILE)
        val themes = if (file.isFile) {
            val text = file.readText()
            runCatching {
                readableJson.decodeFromString<List<ReadableTheme>>(text)
                    .map { it.toKeyboardTheme() }
            }.getOrElse {
                runCatching {
                    fallbackJson.decodeFromString<List<KeyboardTheme>>(text)
                }.getOrElse {
                    runCatching {
                        fallbackJson.decodeFromString<List<CompactTheme>>(text)
                            .map { it.toKeyboardTheme() }
                    }.getOrElse { emptyList() }
                }
            }
        } else {
            emptyList()
        }
        KeyboardThemePresets.setCustomThemes(
            themes.take(MAX_CUSTOM_THEMES).map { it }
        )
    }

    /**
     * 直接导入主题。
     * 相同 id 的主题会被原位替换；槽位未满（含已有同 id 替换）时返回 true。
     */
    fun import(theme: KeyboardTheme): Boolean {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == theme.id }
        when {
            existingIndex >= 0 -> current[existingIndex] = theme
            current.size < MAX_CUSTOM_THEMES -> current.add(theme)
            else -> return false
        }
        applyCustomThemes(current)
        return true
    }

    /** 覆盖指定槽位的用户主题。 */
    fun overwrite(index: Int, theme: KeyboardTheme) {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        if (index in current.indices) {
            current[index] = theme
        } else {
            current.add(theme)
        }
        applyCustomThemes(current)
    }

    private fun applyCustomThemes(themes: List<KeyboardTheme>) {
        val file = File(App.themesDir, THEMES_FILE)
        runCatching {
            file.writeText(
                readableJson.encodeToString(themes.map { ReadableTheme.from(it) })
            )
        }
        KeyboardThemePresets.setCustomThemes(themes.take(MAX_CUSTOM_THEMES))
    }
}