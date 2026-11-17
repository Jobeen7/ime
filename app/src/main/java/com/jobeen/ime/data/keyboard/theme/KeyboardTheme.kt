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
        const val SUNSET_ID = "sunset"

        /** 内置主题：键盘皮肤页的选择列表（自定义主题另在自定义主题组列出）。 */
        val BUILTINS: List<KeyboardTheme>
            get() = listOf(
                KeyboardThemePresets.Amoled,
                KeyboardThemePresets.Light,
                KeyboardThemePresets.Sunset,
                KeyboardThemePresets.Monet,
            )

        val DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled

        fun byId(id: String) = KeyboardThemePresets.ALL.find { it.id == id } ?: DEFAULT
    }
}
