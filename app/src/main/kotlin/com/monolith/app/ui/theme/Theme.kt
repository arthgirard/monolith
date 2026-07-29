package com.monolith.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = MonolithWhite,
    onPrimary = MonolithBlack,
    secondary = MonolithAmber,
    onSecondary = MonolithBlack,
    background = MonolithBlack,
    onBackground = MonolithWhite,
    surface = MonolithNearBlack,
    onSurface = MonolithWhite,
    surfaceVariant = MonolithGrayDark,
    onSurfaceVariant = MonolithGray,
    error = MonolithRed,
    onError = MonolithWhite,
    outline = MonolithGrayDark,
)

private val LightColors = lightColorScheme(
    primary = MonolithBlack,
    onPrimary = MonolithWhite,
    secondary = MonolithAmber,
    onSecondary = MonolithWhite,
    background = MonolithWhite,
    onBackground = MonolithBlack,
    surface = MonolithOffWhite,
    onSurface = MonolithBlack,
    surfaceVariant = MonolithOffWhite,
    onSurfaceVariant = MonolithGray,
    error = MonolithRed,
    onError = MonolithWhite,
    outline = MonolithOffWhite,
)

@Composable
fun MonolithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MonolithTypography,
        content = content,
    )
}
