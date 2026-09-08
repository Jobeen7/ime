package com.ninthsoft.ime.data.keyboard.theme

data class KeyboardTheme(
    val id: String,
    val name: String,
    val colors: KeyboardColors.ColorScheme,
) {
    companion object {
        val PRESETS: List<KeyboardTheme> = KeyboardThemePresets.ALL.take(6)

        val DEFAULT = KeyboardThemePresets.Amoled
        val LIGHT_DEFAULT = KeyboardThemePresets.Light
        val DARK_DEFAULT = KeyboardThemePresets.Amoled

        fun byId(id: String) = KeyboardThemePresets.ALL.find { it.id == id } ?: DEFAULT
    }
}
