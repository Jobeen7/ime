package com.ninthsoft.ime.data.keyboard.theme

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

        val PRESETS: List<KeyboardTheme>
            get() = KeyboardThemePresets.ALL.take(6)

        val DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled
        val LIGHT_DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Light
        val DARK_DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled

        fun byId(id: String) = KeyboardThemePresets.ALL.find { it.id == id } ?: DEFAULT
    }
}
