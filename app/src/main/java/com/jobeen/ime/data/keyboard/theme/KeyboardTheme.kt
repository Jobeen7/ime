package com.jobeen.ime.data.keyboard.theme

import kotlinx.serialization.Serializable

@Serializable
data class KeyboardTheme(
    val id: String,
    val name: String,
    val colors: KeyboardColors.ColorScheme,
) {
    companion object {
        const val DEFAULT_ID = "amoled"
        const val LIGHT_DEFAULT_ID = "light"
        const val DARK_DEFAULT_ID = "amoled"

        /** 库页主题卡：仅 4 内置；已导入自定义主题无卡（文件留盘、无切换入口）。 */
        val BUILTINS: List<KeyboardTheme>
            get() = listOf(
                KeyboardThemePresets.Amoled,
                KeyboardThemePresets.Light,
                KeyboardThemePresets.Sunset,
                KeyboardThemePresets.Monet,
            )

        val DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled
        val LIGHT_DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Light
        val DARK_DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled

        fun byId(id: String) = KeyboardThemePresets.ALL.find { it.id == id } ?: DEFAULT
    }
}
