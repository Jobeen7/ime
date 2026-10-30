package com.jobeen.ime.ui.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.jobeen.ime.data.manager.KeyboardManager.Theme.MODE_DARK
import com.jobeen.ime.data.manager.KeyboardManager.Theme.MODE_LIGHT

object ExpressiveShapes {
    val extraSmall = RoundedCornerShape(4.dp)
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val extraLarge = RoundedCornerShape(24.dp)
}

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,

    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,

    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,

    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,

    background = BackgroundLight,
    onBackground = OnBackgroundLight,

    surface = SurfaceLight,
    onSurface = OnSurfaceLight,

    surfaceContainer = SurfaceContainerLight,

    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,

    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,

    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,

    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,

    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,

    background = BackgroundDark,
    onBackground = OnBackgroundDark,

    surface = SurfaceDark,
    onSurface = OnSurfaceDark,

    surfaceContainer = SurfaceContainerDark,

    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,

    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

private val ExpressiveShapesScheme = Shapes(
    extraSmall = ExpressiveShapes.extraSmall,
    small = ExpressiveShapes.small,
    medium = ExpressiveShapes.medium,
    large = ExpressiveShapes.large,
    extraLarge = ExpressiveShapes.extraLarge,
)

/**
 * 莫奈色卡片面：全部设置页的分组卡片与弹出卡片统一用此色
 * （2026-10-30 莫奈色轮）。与页面主题的动态取色开关解耦：
 * 浅色取动态浅色方案的容器色，深色取动态深色方案的容器色；
 * 动态取色不可用（低版本／取色失败）时回落现有静态容器色。
 * 未被 ImeTheme 包裹时默认浅色静态色（安全值，永不透明）。
 */
val LocalMonetCardColor = compositionLocalOf { SurfaceContainerLight }

/**
 * 莫奈色卡片面取值（纯函数，可 JVM 单测）：动态容器色优先，
 * 无动态色时按深浅回落静态容器色。
 */
fun monetCardContainerColor(dynamicContainer: Color?, dark: Boolean): Color =
    dynamicContainer ?: if (dark) SurfaceContainerDark else SurfaceContainerLight

@SuppressLint("ContextCastToActivity")
@Composable
fun ImeTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        MODE_LIGHT -> false
        MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 卡片面与页面动态开关解耦：只要系统支持动态取色就取动态容器色。
    val context = LocalContext.current
    val monetCardColor = remember(darkTheme) {
        val dynamicContainer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    if (darkTheme) dynamicDarkColorScheme(context).surfaceContainer
                    else dynamicLightColorScheme(context).surfaceContainer
                }.getOrNull()
            } else {
                null
            }
        monetCardContainerColor(dynamicContainer, darkTheme)
    }

    CompositionLocalProvider(LocalMonetCardColor provides monetCardColor) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ExpressiveShapesScheme,
            content = content,
        )
    }

    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            val bgColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = bgColor
                @Suppress("DEPRECATION")
                window.navigationBarColor = bgColor
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                !darkTheme
        }
    }
}
