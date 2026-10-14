package com.yinian.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.yinian.android.data.prefs.PrefsRepository

private val LightColors = lightColorScheme(
    primary = BrandAccent,
    onPrimary = VoiceText,
    primaryContainer = AccentSurface,
    onPrimaryContainer = TextPrimary,
    secondary = AuxBlueText,
    onSecondary = AuxBlueSurface,
    secondaryContainer = AuxBlueSurface,
    onSecondaryContainer = AuxBlueText,
    tertiary = AuxPinkText,
    onTertiary = AuxPinkSurface,
    tertiaryContainer = AuxPinkSurface,
    onTertiaryContainer = AuxPinkText,
    background = MintWhite,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceBacklight,
    onSurfaceVariant = TextSecondary,
    outline = EdgeThin,
    outlineVariant = DividerSoft
)

private val DarkColors = darkColorScheme(
    primary = NightAccent,
    onPrimary = NightVoiceText,
    primaryContainer = NightBacklight,
    onPrimaryContainer = NightText,
    secondary = AuxBlueText,
    onSecondary = AuxBlueSurface,
    secondaryContainer = NightBacklight,
    onSecondaryContainer = NightTextSecondary,
    tertiary = AuxPinkText,
    onTertiary = AuxPinkSurface,
    tertiaryContainer = NightBacklight,
    onTertiaryContainer = NightTextSecondary,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightBacklight,
    onSurfaceVariant = NightTextSecondary,
    outline = NightBorder,
    outlineVariant = NightEdge
)

@Composable
fun YinianTheme(
    themeMode: String = PrefsRepository.THEME_SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        PrefsRepository.THEME_LIGHT -> false
        PrefsRepository.THEME_DARK -> true
        else -> systemDark
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = YinianTypography,
        shapes = YinianShapes,
        content = content
    )
}
