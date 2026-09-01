package com.ninthsoft.ime.data.keyboard.theme

data class KeyboardTheme(
    val id: String,
    val name: String,
    val colors: KeyboardColors.ColorScheme,
) {
    companion object {
        val PRESETS = listOf(
            KeyboardThemePresets.Amoled,
            KeyboardThemePresets.Light,
            KeyboardThemePresets.Forest,
            KeyboardThemePresets.Ocean,
            KeyboardThemePresets.Sunset,
            KeyboardThemePresets.Plum,
        )

        val DEFAULT = KeyboardThemePresets.Amoled
        val LIGHT_DEFAULT = KeyboardThemePresets.Light
        val DARK_DEFAULT = KeyboardThemePresets.Amoled

        fun byId(id: String) = PRESETS.find { it.id == id } ?: DEFAULT
    }
}
