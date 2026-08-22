package com.monolith.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Every M3 role is set explicitly in all three schemes. Any role left unset falls back to
// Material's baseline purple, which is how amber ended up re-specified inline at half a dozen
// call sites. Filling the roles is what lets those overrides be deleted: components pick the
// right color up from the scheme on their own.

private val LightColors = lightColorScheme(
    primary = MonolithBlack,
    onPrimary = MonolithWhite,
    primaryContainer = MonolithOffWhite,
    onPrimaryContainer = MonolithBlack,
    inversePrimary = MonolithWhite,

    // The single accent. `secondary` and `tertiary` are deliberately the same family rather than
    // two competing hues -- amber only appears where a decision is being made.
    secondary = AmberDeep,
    onSecondary = MonolithCard,
    secondaryContainer = AmberWashLight,
    onSecondaryContainer = AmberDeep,
    tertiary = AmberDeep,
    onTertiary = MonolithCard,
    tertiaryContainer = AmberWashLight,
    onTertiaryContainer = AmberDeep,

    background = MonolithWhite,
    onBackground = MonolithBlack,
    surface = MonolithCard,
    onSurface = MonolithBlack,
    // Pinned to the surface. Left unset it defaults to the baseline purple, which elevated
    // surfaces (dialogs, menus) then blend into their background.
    surfaceTint = MonolithCard,
    surfaceVariant = MonolithOffWhite,
    onSurfaceVariant = MonolithGrayLight,
    surfaceDim = MonolithOffWhite,
    surfaceBright = MonolithCard,
    surfaceContainerLowest = MonolithCard,
    surfaceContainerLow = MonolithCard,
    surfaceContainer = MonolithWhite,
    surfaceContainerHigh = MonolithDivider,
    surfaceContainerHighest = MonolithOffWhite,

    error = MonolithRed,
    onError = MonolithWhite,
    errorContainer = RedWashLight,
    onErrorContainer = MonolithRed,

    outline = MonolithOffWhite,
    outlineVariant = MonolithDivider,
    scrim = MonolithBlack,

    // Snackbars read these. Unset, they were baseline grey-purple.
    inverseSurface = MonolithBlack,
    inverseOnSurface = MonolithWhite,
)

private val DarkColors = darkColorScheme(
    primary = MonolithWhite,
    onPrimary = MonolithBlack,
    primaryContainer = MonolithGrayDark,
    onPrimaryContainer = MonolithWhite,
    inversePrimary = MonolithBlack,

    secondary = MonolithAmber,
    onSecondary = MonolithBlack,
    secondaryContainer = AmberWashDark,
    onSecondaryContainer = MonolithAmber,
    tertiary = MonolithAmber,
    onTertiary = MonolithBlack,
    tertiaryContainer = AmberWashDark,
    onTertiaryContainer = MonolithAmber,

    background = MonolithBlack,
    onBackground = MonolithWhite,
    surface = MonolithNearBlack,
    onSurface = MonolithWhite,
    surfaceTint = MonolithNearBlack,
    surfaceVariant = MonolithSunken,
    onSurfaceVariant = MonolithGray,
    surfaceDim = MonolithBlack,
    surfaceBright = MonolithGrayDark,
    surfaceContainerLowest = MonolithBlack,
    surfaceContainerLow = MonolithNearBlack,
    surfaceContainer = MonolithNearBlack,
    surfaceContainerHigh = MonolithSunken,
    surfaceContainerHighest = MonolithGrayDark,

    error = MonolithRed,
    onError = MonolithWhite,
    errorContainer = RedWashDark,
    onErrorContainer = RedSoft,

    outline = MonolithGrayDark,
    outlineVariant = MonolithSunken,
    scrim = MonolithBlack,

    inverseSurface = MonolithWhite,
    inverseOnSurface = MonolithBlack,
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
        shapes = MonolithShapes,
    ) {
        // MaterialTheme does not provide LocalTextStyle, so anything reading it falls back to
        // TextStyle.Default -- i.e. Roboto. That is what a text field's *typed* input uses, while
        // its placeholder picks up bodyLarge from the decoration box. Providing it here keeps the
        // two in the same family.
        CompositionLocalProvider(LocalTextStyle provides MonolithTypography.bodyLarge, content = content)
    }
}
