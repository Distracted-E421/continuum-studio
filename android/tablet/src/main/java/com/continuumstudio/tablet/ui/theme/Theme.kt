package com.continuumstudio.tablet.ui.theme

import android.app.Activity
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
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFCE93D8),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF7B1FA2),
    tertiary = Color(0xFF00796B),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
)

private val FiveFootDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFEB3B),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFF76FF03),
    background = Color(0xFF000000),
    surface = Color(0xFF1A1A1A),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

data class TabletTypography(
    val bodyLarge: androidx.compose.ui.text.TextStyle,
    val bodyMedium: androidx.compose.ui.text.TextStyle,
    val titleLarge: androidx.compose.ui.text.TextStyle,
    val titleMedium: androidx.compose.ui.text.TextStyle,
    val headlineLarge: androidx.compose.ui.text.TextStyle,
    val labelLarge: androidx.compose.ui.text.TextStyle,
)

val LocalTabletTypography = staticCompositionLocalOf {
    TabletTypography(
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp),
        titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
        headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 32.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
    )
}

val LocalIsFiveFootMode = staticCompositionLocalOf { false }

@Composable
fun ContinuumTabletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isFiveFootMode: Boolean = false,
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        isFiveFootMode -> FiveFootDarkColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val baseFontSize = if (isFiveFootMode) 32.sp else 16.sp
    val scaledFontSize = baseFontSize * fontScale
    
    val typography = TabletTypography(
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = scaledFontSize),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = scaledFontSize * 0.875f),
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = scaledFontSize * 1.375f),
        titleMedium = androidx.compose.ui.text.TextStyle(fontSize = scaledFontSize * 1.125f),
        headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = scaledFontSize * 2f),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = scaledFontSize * 0.875f),
    )
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !isFiveFootMode
        }
    }

    CompositionLocalProvider(
        LocalTabletTypography provides typography,
        LocalIsFiveFootMode provides isFiveFootMode,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
