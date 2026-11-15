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

            get() = listOf(
                KeyboardThemePresets.Amoled,
                KeyboardThemePresets.Light,
                KeyboardThemePresets.Sunset,
                KeyboardThemePresets.Monet,
            )

        val DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled
            get() = KeyboardThemePresets.Light
            get() = KeyboardThemePresets.Amoled

        fun byId(id: String) = KeyboardThemePresets.ALL.find { it.id == id } ?: DEFAULT
    }
}
