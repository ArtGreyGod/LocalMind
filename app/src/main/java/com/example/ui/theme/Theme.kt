package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = InkPrimaryDark,
    onPrimary = OnInkPrimaryDark,
    primaryContainer = InkContainerDark,
    onPrimaryContainer = OnInkContainerDark,
    secondary = CobaltAccentDark,
    onSecondary = OnCobaltAccentDark,
    secondaryContainer = CobaltContainerDark,
    onSecondaryContainer = OnCobaltContainerDark,
    tertiary = AmberTertiary,
    onTertiary = OnAmberTertiary,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = OnAmberContainer,
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianSurfaceLowest,
    surfaceContainerLow = ObsidianSurfaceLow,
    surfaceContainer = ObsidianSurface,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = ErrorRedDark,
    onError = OnErrorRedDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = InkPrimaryLight,
    onPrimary = OnInkPrimaryLight,
    primaryContainer = InkContainerLight,
    onPrimaryContainer = OnInkContainerLight,
    secondary = CobaltAccentLight,
    onSecondary = OnCobaltAccentLight,
    secondaryContainer = CobaltContainerLight,
    onSecondaryContainer = OnCobaltContainerLight,
    tertiary = AmberTertiaryLight,
    onTertiary = OnAmberTertiaryLight,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = OnAmberContainerLight,
    background = DaylightBackground,
    onBackground = TextPrimaryLight,
    surface = DaylightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = DaylightSurfaceLowest,
    surfaceContainerLow = DaylightSurfaceLow,
    surfaceContainer = DaylightSurface,
    surfaceContainerHigh = DaylightSurfaceHigh,
    surfaceContainerHighest = DaylightSurfaceHighest,
    outline = DaylightOutline,
    outlineVariant = DaylightOutlineVariant,
    error = ErrorRedLight,
    onError = OnErrorRedLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

data class SemanticStatusColors(
    val liveEmerald: Color,
    val floatingSurface: Color,
    val isDark: Boolean
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticStatusColors(
        liveEmerald = LiveEmeraldLight,
        floatingSurface = DaylightSurfaceLowest,
        isDark = false
    )
}

@Composable
fun LocalMindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val semanticColors = if (darkTheme) {
        SemanticStatusColors(
            liveEmerald = LiveEmeraldDark,
            floatingSurface = Color(0xFF1A1A22),
            isDark = true
        )
    } else {
        SemanticStatusColors(
            liveEmerald = LiveEmeraldLight,
            floatingSurface = Color(0xFFFFFFFF),
            isDark = false
        )
    }

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
