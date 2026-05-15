package com.example.continuumstudio.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
data class XRTypographyConfig(
    val fontFamily: XRFontFamily = XRFontFamily.JETBRAINS_MONO,
    val baseSize: Float = 14f,
    val glassesScale: Float = 1.5f,
    val phoneScale: Float = 1.0f,
    val lineHeightMultiplier: Float = 1.4f,
    val letterSpacing: Float = 0f
)

enum class XRFontFamily(val displayName: String, val fontFamilyGetter: () -> FontFamily) {
    SYSTEM_DEFAULT("System Default", { FontFamily.Default }),
    JETBRAINS_MONO("JetBrains Mono (Nerd)", { FontFamily.Monospace }),
    FIRA_CODE("Fira Code (Nerd)", { FontFamily.Monospace }),
    HACK("Hack (Nerd)", { FontFamily.Monospace }),
    SOURCE_CODE_PRO("Source Code Pro", { FontFamily.Monospace }),
    ROBOTO_MONO("Roboto Mono", { FontFamily.Monospace }),
    MONOSPACE("Monospace", { FontFamily.Monospace });

    fun toFontFamily(): FontFamily = fontFamilyGetter()
}

@Immutable
data class XRTextStyles(
    val glassesTitle: TextStyle,
    val glassesBody: TextStyle,
    val glassesCaption: TextStyle,
    val glassesOption: TextStyle,
    val glassesShortcut: TextStyle,
    val phoneTitle: TextStyle,
    val phoneBody: TextStyle,
    val phoneCaption: TextStyle,
    val phoneButton: TextStyle,
    val phoneInput: TextStyle,
    val widgetLabel: TextStyle,
    val widgetValue: TextStyle
)

val LocalXRTypography = staticCompositionLocalOf<XRTextStyles> {
    error("No XRTypography provided")
}

fun createXRTextStyles(config: XRTypographyConfig): XRTextStyles {
    val fontFamily = config.fontFamily.toFontFamily()
    val baseSize = config.baseSize
    val glassesScale = config.glassesScale
    val phoneScale = config.phoneScale
    val letterSpacing = config.letterSpacing.sp

    return XRTextStyles(
        glassesTitle = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (baseSize * glassesScale * 1.8f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * glassesScale * 1.8f * config.lineHeightMultiplier).sp
        ),
        glassesBody = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (baseSize * glassesScale).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * glassesScale * config.lineHeightMultiplier).sp
        ),
        glassesCaption = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Light,
            fontSize = (baseSize * glassesScale * 0.75f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * glassesScale * 0.75f * config.lineHeightMultiplier).sp
        ),
        glassesOption = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (baseSize * glassesScale * 1.1f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * glassesScale * 1.1f * config.lineHeightMultiplier).sp
        ),
        glassesShortcut = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (baseSize * glassesScale).sp,
            letterSpacing = (config.letterSpacing + 0.5f).sp,
            lineHeight = (baseSize * glassesScale * config.lineHeightMultiplier).sp
        ),
        phoneTitle = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (baseSize * phoneScale * 1.4f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * phoneScale * 1.4f * config.lineHeightMultiplier).sp
        ),
        phoneBody = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (baseSize * phoneScale).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * phoneScale * config.lineHeightMultiplier).sp
        ),
        phoneCaption = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Light,
            fontSize = (baseSize * phoneScale * 0.85f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * phoneScale * 0.85f * config.lineHeightMultiplier).sp
        ),
        phoneButton = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (baseSize * phoneScale * 1.1f).sp,
            letterSpacing = (config.letterSpacing + 0.25f).sp,
            lineHeight = (baseSize * phoneScale * 1.1f * config.lineHeightMultiplier).sp
        ),
        phoneInput = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (baseSize * phoneScale).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * phoneScale * config.lineHeightMultiplier).sp
        ),
        widgetLabel = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (baseSize * phoneScale * 0.9f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * phoneScale * 0.9f * config.lineHeightMultiplier).sp
        ),
        widgetValue = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (baseSize * phoneScale * 1.2f).sp,
            letterSpacing = letterSpacing,
            lineHeight = (baseSize * phoneScale * 1.2f * config.lineHeightMultiplier).sp
        )
    )
}

@Composable
fun XRTypographyProvider(
    config: XRTypographyConfig,
    content: @Composable () -> Unit
) {
    val styles = createXRTextStyles(config)
    CompositionLocalProvider(LocalXRTypography provides styles) {
        content()
    }
}

object XRTypographyPresets {
    val default = XRTypographyConfig()

    val compact = XRTypographyConfig(
        baseSize = 12f,
        glassesScale = 1.3f,
        phoneScale = 0.9f
    )

    val large = XRTypographyConfig(
        baseSize = 16f,
        glassesScale = 1.7f,
        phoneScale = 1.1f
    )

    val extraLarge = XRTypographyConfig(
        baseSize = 18f,
        glassesScale = 2.0f,
        phoneScale = 1.2f
    )

    val monoHacker = XRTypographyConfig(
        fontFamily = XRFontFamily.HACK,
        baseSize = 13f,
        glassesScale = 1.4f,
        letterSpacing = 0.5f
    )

    val nerdProgrammer = XRTypographyConfig(
        fontFamily = XRFontFamily.JETBRAINS_MONO,
        baseSize = 14f,
        glassesScale = 1.5f,
        letterSpacing = 0.25f
    )
}
