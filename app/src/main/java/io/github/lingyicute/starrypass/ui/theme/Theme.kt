/*
 * StarryPass Android — Material You (M3 Dynamic Color + Smooth Animated Tonal Theme)
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.lingyicute.starrypass.data.ThemePreference

private const val THEME_TRANSITION_MS = 360

private val StarryLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary
)

private val StarryDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary
)

@Composable
private fun animateThemeColor(target: Color, label: String): Color {
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = THEME_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = label
    ).value
}

@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animateThemeColor(primary, "primary"),
    onPrimary = animateThemeColor(onPrimary, "onPrimary"),
    primaryContainer = animateThemeColor(primaryContainer, "primaryContainer"),
    onPrimaryContainer = animateThemeColor(onPrimaryContainer, "onPrimaryContainer"),
    inversePrimary = animateThemeColor(inversePrimary, "inversePrimary"),
    secondary = animateThemeColor(secondary, "secondary"),
    onSecondary = animateThemeColor(onSecondary, "onSecondary"),
    secondaryContainer = animateThemeColor(secondaryContainer, "secondaryContainer"),
    onSecondaryContainer = animateThemeColor(onSecondaryContainer, "onSecondaryContainer"),
    tertiary = animateThemeColor(tertiary, "tertiary"),
    onTertiary = animateThemeColor(onTertiary, "onTertiary"),
    tertiaryContainer = animateThemeColor(tertiaryContainer, "tertiaryContainer"),
    onTertiaryContainer = animateThemeColor(onTertiaryContainer, "onTertiaryContainer"),
    background = animateThemeColor(background, "background"),
    onBackground = animateThemeColor(onBackground, "onBackground"),
    surface = animateThemeColor(surface, "surface"),
    onSurface = animateThemeColor(onSurface, "onSurface"),
    surfaceVariant = animateThemeColor(surfaceVariant, "surfaceVariant"),
    onSurfaceVariant = animateThemeColor(onSurfaceVariant, "onSurfaceVariant"),
    surfaceTint = animateThemeColor(surfaceTint, "surfaceTint"),
    inverseSurface = animateThemeColor(inverseSurface, "inverseSurface"),
    inverseOnSurface = animateThemeColor(inverseOnSurface, "inverseOnSurface"),
    error = animateThemeColor(error, "error"),
    onError = animateThemeColor(onError, "onError"),
    errorContainer = animateThemeColor(errorContainer, "errorContainer"),
    onErrorContainer = animateThemeColor(onErrorContainer, "onErrorContainer"),
    outline = animateThemeColor(outline, "outline"),
    outlineVariant = animateThemeColor(outlineVariant, "outlineVariant"),
    scrim = animateThemeColor(scrim, "scrim"),
    surfaceBright = animateThemeColor(surfaceBright, "surfaceBright"),
    surfaceDim = animateThemeColor(surfaceDim, "surfaceDim"),
    surfaceContainer = animateThemeColor(surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = animateThemeColor(surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = animateThemeColor(surfaceContainerHighest, "surfaceContainerHighest"),
    surfaceContainerLow = animateThemeColor(surfaceContainerLow, "surfaceContainerLow"),
    surfaceContainerLowest = animateThemeColor(surfaceContainerLowest, "surfaceContainerLowest")
)

@Composable
fun StarryPassTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val context = LocalContext.current
    val targetColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> StarryDarkColorScheme
        else -> StarryLightColorScheme
    }
    val animatedColorScheme = targetColorScheme.animated()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDark
            insetsController.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = animatedColorScheme,
        typography = StarryTypography,
        content = content
    )
}
