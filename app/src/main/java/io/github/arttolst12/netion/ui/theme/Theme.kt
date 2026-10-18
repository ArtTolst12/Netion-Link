package io.github.arttolst12.netion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val DarkColorScheme = darkColorScheme(
    primary              = AppPurple,
    onPrimary            = Color.Black,
    secondary            = AppPurpleDim,
    onSecondary          = Color.White,
    tertiary             = WarningDark,
    onTertiary           = OnWarningDark,
    tertiaryContainer    = WarningContainerDark,
    onTertiaryContainer  = OnWarningContainerDark,
    background           = AppBackground,
    onBackground         = AppOnBackground,
    surface              = AppSurface,
    onSurface            = AppOnSurface,
    surfaceVariant       = AppSurface,
    error                = AppError,
    onError              = AppOnError,
)

private val LightColorScheme = lightColorScheme(
    primary              = AppPurpleDim,
    onPrimary            = Color.White,
    secondary            = AppPurple,
    onSecondary          = Color.Black,
    tertiary             = WarningLight,
    onTertiary           = OnWarningLight,
    tertiaryContainer    = WarningContainerLight,
    onTertiaryContainer  = OnWarningContainerLight,
    background           = LightBackground,
    onBackground         = LightOnBackground,
    surface              = LightSurface,
    onSurface            = LightOnSurface,
    surfaceVariant       = LightSurface,
)

val LocalDarkTheme = staticCompositionLocalOf { true }

@Composable
fun NetionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Красим системные бары в цвет фона — устраняет «квадрат» при смене темы
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val bgColor = colorScheme.background.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = bgColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = bgColor
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            content     = content
        )
    }
}